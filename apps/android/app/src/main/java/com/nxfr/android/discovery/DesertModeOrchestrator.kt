package com.nxfr.android.discovery

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Desert mode state machine. Wraps P2P + SoftAP.
sealed class DesertState {
    data object Idle : DesertState()
    data object LocationRequired : DesertState()  // Android < 13 needs GPS ON
    data class Tier1Scanning(val secondsRemaining: Int) : DesertState()
    data class Tier1PeersFound(val peers: List<P2pPeer>) : DesertState()
    data object Tier1Connecting : DesertState()
    data class Tier2Hosting(
        val ssid: String,
        val passphrase: String?,
        val hostIp: String,
        val qrPayload: String
    ) : DesertState()
    data object Tier2Starting : DesertState()
    data object Tier2Joining : DesertState()
    data class Tier3QrFallback(val reason: String) : DesertState()
    data class Connected(val peerIp: String, val tier: Int, val isGroupOwner: Boolean) : DesertState()
    data class Failed(val reason: String) : DesertState()
}

class DesertModeOrchestrator(
    private val p2pManager: NxfrP2pManager,
    private val softApManager: NxfrSoftApManager
) {
    companion object {
        private const val TAG = "DesertOrch"
        private const val TIER1_TIMEOUT_S = 10
        private const val TIER2_HOST_TIMEOUT_S = 8
    }

    private val _state = MutableStateFlow<DesertState>(DesertState.Idle)
    val state: StateFlow<DesertState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tierJob: Job? = null
    private var p2pObserverJob: Job? = null
    private var softApObserverJob: Job? = null
    private var context: Context? = null
    private var localAid: String = ""
    private var localName: String = ""

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Start the three-tier discovery cascade.
     * Call with the local advertised_id and device name.
     */
    fun start(localAid: String, localName: String) {
        this.localAid = localAid
        this.localName = localName

        // Pre-flight: check location on Android < 13
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                _state.value = DesertState.LocationRequired
                return
            }
        }

        startTier1()
    }

    /**
     * Retry after location was enabled.
     */
    fun retryAfterLocation() {
        startTier1()
    }

    // ── Tier 1: Wi-Fi Direct ──────────────────────────────────

    private fun startTier1() {
        Log.i(TAG, "Starting Tier 1: Wi-Fi Direct discovery")
        _state.value = DesertState.Tier1Scanning(TIER1_TIMEOUT_S)

        p2pManager.startDiscovery(localAid, localName)

        // Observe P2P state changes
        p2pObserverJob?.cancel()
        p2pObserverJob = scope.launch {
            p2pManager.state.collect { p2pState ->
                when (p2pState) {
                    is P2pState.PeersFound -> {
                        if (_state.value is DesertState.Tier1Scanning) {
                            tierJob?.cancel()
                            _state.value = DesertState.Tier1PeersFound(p2pState.peers)
                        }
                    }
                    is P2pState.Forming -> {
                        _state.value = DesertState.Tier1Connecting
                    }
                    is P2pState.Ready -> {
                        tierJob?.cancel()
                        _state.value = DesertState.Connected(
                            peerIp = p2pState.goIp,
                            tier = 1,
                            isGroupOwner = p2pState.isGO
                        )
                    }
                    is P2pState.Failed -> {
                        // If we're still in Tier 1, escalate to Tier 2
                        val currentState = _state.value
                        if (currentState is DesertState.Tier1Scanning ||
                            currentState is DesertState.Tier1Connecting) {
                            Log.i(TAG, "Tier 1 failed: ${p2pState.reason} — escalating to Tier 2")
                            tierJob?.cancel()
                            startTier2()
                        }
                    }
                    else -> {}
                }
            }
        }

        // Countdown timer for Tier 1 timeout
        tierJob?.cancel()
        tierJob = scope.launch {
            for (remaining in TIER1_TIMEOUT_S downTo 1) {
                if (_state.value !is DesertState.Tier1Scanning) return@launch
                _state.value = DesertState.Tier1Scanning(remaining)
                delay(1000)
            }
            // Timeout — no peers found
            if (_state.value is DesertState.Tier1Scanning) {
                Log.i(TAG, "Tier 1 timeout — no Wi-Fi Direct peers found. Escalating to Tier 2.")
                p2pManager.cancelDiscoveryOnly()
                startTier2()
            }
        }
    }

    /**
     * User tapped a discovered peer in Tier 1.
     */
    fun connectToPeer(peer: P2pPeer) {
        _state.value = DesertState.Tier1Connecting
        p2pManager.connect(peer)
    }

    // ── Tier 2: SoftAP Host ──────────────────────────────────

    private fun startTier2() {
        Log.i(TAG, "Starting Tier 2: SoftAP hosting")
        _state.value = DesertState.Tier2Starting

        softApManager.resetState()
        softApManager.startHotspot()

        // Observe SoftAP state
        softApObserverJob?.cancel()
        softApObserverJob = scope.launch {
            softApManager.hostState.collect { apState ->
                when (apState) {
                    is SoftApState.Active -> {
                        tierJob?.cancel()
                        val qr = buildDesertQrPayload(apState.ssid, apState.passphrase, apState.hostIp)
                        _state.value = DesertState.Tier2Hosting(
                            ssid = apState.ssid,
                            passphrase = apState.passphrase,
                            hostIp = apState.hostIp,
                            qrPayload = qr
                        )
                        Log.i(TAG, "Tier 2 active: SSID=${apState.ssid}, hostIp=${apState.hostIp}")
                    }
                    is SoftApState.Failed -> {
                        Log.w(TAG, "Tier 2 host failed: ${apState.reason} — falling back to Tier 3 QR")
                        tierJob?.cancel()
                        _state.value = DesertState.Tier3QrFallback(apState.reason)
                    }
                    else -> {}
                }
            }
        }

        // Also observe client state (in case user scans a QR while we're hosting)
        scope.launch {
            softApManager.clientState.collect { clientState ->
                if (clientState is ClientJoinState.Connected) {
                    tierJob?.cancel()
                    _state.value = DesertState.Connected(
                        peerIp = clientState.hostIp,
                        tier = 2,
                        isGroupOwner = false
                    )
                }
            }
        }

        // Timeout for Tier 2 host startup
        tierJob?.cancel()
        tierJob = scope.launch {
            delay(TIER2_HOST_TIMEOUT_S * 1000L)
            if (_state.value is DesertState.Tier2Starting) {
                Log.w(TAG, "Tier 2 host timeout — falling back to Tier 3")
                _state.value = DesertState.Tier3QrFallback("Hotspot failed to start within ${TIER2_HOST_TIMEOUT_S}s")
            }
        }
    }

    /**
     * User scanned a QR code from the other device's Tier 2 hosting screen.
     * Joins the specified SoftAP network.
     */
    fun joinFromQr(ssid: String, passphrase: String?, hostIp: String, port: Int = 17394) {
        Log.i(TAG, "Joining from QR: SSID=$ssid, hostIp=$hostIp:$port")
        _state.value = DesertState.Tier2Joining

        // If we're hosting, stop our hotspot first
        softApManager.stopHotspot()

        softApManager.joinNetwork(ssid, passphrase)

        // The clientState observer (launched in startTier2 or here) will catch Connected
        softApObserverJob?.cancel()
        softApObserverJob = scope.launch {
            softApManager.clientState.collect { clientState ->
                when (clientState) {
                    is ClientJoinState.Connected -> {
                        _state.value = DesertState.Connected(
                            peerIp = clientState.hostIp,
                            tier = 2,
                            isGroupOwner = false
                        )
                    }
                    is ClientJoinState.Failed -> {
                        _state.value = DesertState.Failed("Failed to join network: ${clientState.reason}")
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────

    fun teardown() {
        tierJob?.cancel()
        p2pObserverJob?.cancel()
        softApObserverJob?.cancel()
        p2pManager.teardown()
        softApManager.teardown()
        _state.value = DesertState.Idle
        Log.i(TAG, "Desert Mode teardown complete")
    }

    fun reset() {
        tierJob?.cancel()
        p2pObserverJob?.cancel()
        softApObserverJob?.cancel()
        p2pManager.cancelDiscoveryOnly()
        softApManager.resetState()
        _state.value = DesertState.Idle
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun buildDesertQrPayload(ssid: String, passphrase: String?, hostIp: String): String {
        val pw = passphrase ?: ""
        return "nxfr://desert?ssid=${encode(ssid)}&pw=${encode(pw)}&ip=${encode(hostIp)}&port=17394"
    }

    private fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8")
    }
}
