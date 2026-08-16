package com.nxfr.android.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.net.*
import java.time.LocalDate

/**
 * UDP beacon discovery — LocalSend-style instant device finding.
 *
 * Tier 0 in the discovery ladder: fastest path, works on hotspots
 * where mDNS is blocked.
 *
 * Protocol: broadcast a small JSON datagram on port 17395
 * to all site-local directed broadcast addresses + multicast 224.0.0.251.
 * Peers do the same; we listen on 0.0.0.0:17395.
 *
 * BEACON INTERVAL STRATEGY:
 *   - ACTIVE (device picker visible): 1s — discovery needs to feel instant
 *   - BACKGROUND (service running, no UI): 5s — still discoverable, less aggressive
 *   - LOW_POWER (deep background, no transfer): 30s — minimal keep-alive
 *
 * PRIVACY: The beacon broadcasts a rotating advertised_id (HKDF-derived,
 * changes daily) — NEVER the real device_id. The real identity is only
 * revealed inside the TLS 1.3 handshake (encrypted, authenticated).
 *
 * Beacon format (JSON, < 256 bytes):
 *   {"v":1,"advertised_id":"hex16","name":"...","plat":"android","tcp_port":17394}
 */
class UdpBeacon(private val context: Context) {

    /**
     * Beacon broadcast frequency mode.
     *
     * Call [setBeaconMode] to switch between modes based on app lifecycle.
     */
    enum class BeaconMode(val intervalMs: Long) {
        /** Device picker is visible — discovery must feel instant. */
        ACTIVE(1_000L),
        /** App backgrounded, service running, no active transfer. */
        BACKGROUND(5_000L),
        /** Deep background — minimal keep-alive, rely on mDNS for discovery. */
        LOW_POWER(30_000L),
    }

    companion object {
        private const val TAG = "UdpBeacon"
        const val BEACON_PORT = 17395
        const val TCP_PORT = 17394
        private const val PEER_EXPIRY_MS = 4000L
        private const val MULTICAST_ADDR = "224.0.0.251"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _peers = MutableStateFlow<List<DeviceUiModel>>(emptyList())
    val peers: StateFlow<List<DeviceUiModel>> = _peers.asStateFlow()

    private var listenerSocket: DatagramSocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var expiryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // advertised_id → (DeviceUiModel, lastSeenMs)
    private val peerMap = mutableMapOf<String, Pair<DeviceUiModel, Long>>()

    var localDeviceId: String = ""
    var localDeviceName: String = "NXFR-Android"

    /** Rotating advertised_id for privacy; computed on start(). */
    private var localAdvertisedId: String = ""

    /** Current beacon broadcast mode. Defaults to ACTIVE for backward compatibility. */
    @Volatile
    private var currentMode: BeaconMode = BeaconMode.ACTIVE

    /**
     * Set the beacon broadcast frequency mode.
     *
     * Call this from [NxfrService] when:
     * - App comes to foreground / device picker opens → [BeaconMode.ACTIVE]
     * - App goes to background, service still running → [BeaconMode.BACKGROUND]
     * - No UI, no active transfer for a while → [BeaconMode.LOW_POWER]
     * - App re-opens → [BeaconMode.ACTIVE]
     */
    fun setBeaconMode(mode: BeaconMode) {
        val previous = currentMode
        currentMode = mode
        if (previous != mode) {
            Log.i(TAG, "Beacon mode: $previous → $mode (interval: ${mode.intervalMs}ms)")
        }
    }

    /** Start announcing + listening. */
    fun start() {
        if (listenJob?.isActive == true) return
        // Launch all I/O off the calling thread.
        scope.launch {
            try {
                Log.i(TAG, "Starting UDP beacon on port $BEACON_PORT")

                // Compute today's rotating advertised_id via FFI.
                localAdvertisedId = computeAdvertisedId()
                Log.i(TAG, "Beacon advertised_id=${localAdvertisedId.take(8)}…")

                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("nxfr_beacon").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                listenerSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(BEACON_PORT))
                    broadcast = true
                }
                listenJob = launch { listenLoop() }
                announceJob = launch { announceLoop() }
                expiryJob = launch { expiryLoop() }
            } catch (e: Throwable) {
                Log.e(TAG, "Beacon start failed (non-fatal): ${e.message}")
                // Beacon silently off; NSD + probe still work.
                // Release multicast lock if start failed partway through.
                try { multicastLock?.release() } catch (_: Exception) {}
                multicastLock = null
            }
        }
    }

    /** Stop all beacon activity. */
    fun stop() {
        announceJob?.cancel()
        listenJob?.cancel()
        expiryJob?.cancel()
        announceJob = null
        listenJob = null
        expiryJob = null

        try { listenerSocket?.close() } catch (_: Exception) {}
        listenerSocket = null

        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null

        synchronized(peerMap) { peerMap.clear() }
        _peers.value = emptyList()
        Log.i(TAG, "Stopped UDP beacon")
    }

    // ── Announce loop ────────────────────────────────────────────────

    private suspend fun announceLoop() {
        val socket = listenerSocket ?: return
        while (coroutineContext.isActive) {
            try {
                val payload = buildBeaconPayload()
                val data = payload.toByteArray(Charsets.UTF_8)

                // Send to all directed broadcast addresses.
                for (broadcast in getDirectedBroadcasts()) {
                    try {
                        val packet = DatagramPacket(
                            data, data.size,
                            InetAddress.getByName(broadcast), BEACON_PORT
                        )
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.d(TAG, "Broadcast to $broadcast failed: ${e.message}")
                    }
                }

                // Also multicast.
                try {
                    val packet = DatagramPacket(
                        data, data.size,
                        InetAddress.getByName(MULTICAST_ADDR), BEACON_PORT
                    )
                    socket.send(packet)
                } catch (e: Exception) {
                    Log.d(TAG, "Multicast send failed: ${e.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Announce error: ${e.message}")
            }
            // Use the current mode's interval — reads are atomic (@Volatile).
            delay(currentMode.intervalMs)
        }
    }

    // ── Listen loop ──────────────────────────────────────────────────

    private suspend fun listenLoop() {
        val socket = listenerSocket ?: return
        val buf = ByteArray(512)
        while (coroutineContext.isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                // recv is blocking, but we're on Dispatchers.IO
                withContext(Dispatchers.IO) {
                    socket.soTimeout = 1000 // 1s timeout to allow cancellation check
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        return@withContext
                    }
                    handleBeacon(packet)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SocketException && socket.isClosed) break
                Log.d(TAG, "Listen error: ${e.message}")
            }
        }
    }

    private fun handleBeacon(packet: DatagramPacket) {
        try {
            val json = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val obj = JSONObject(json)
            val version = obj.optInt("v", 0)
            if (version != 1) return

            // Privacy: beacons carry advertised_id (rotating), NOT device_id.
            val advertisedId = obj.optString("advertised_id", "")
            if (advertisedId.isEmpty() || advertisedId == localAdvertisedId) return // Ignore self.

            val name = obj.optString("name", "Unknown")
            val plat = obj.optString("plat", "unknown")
            val tcpPort = obj.optInt("tcp_port", TCP_PORT)
            val host = packet.address.hostAddress ?: return

            val device = DeviceUiModel(
                name = name,
                host = host,
                port = tcpPort,
                deviceId = advertisedId, // Use advertised_id for dedup; real id comes via TLS.
                platform = plat,
                isPaired = false,
                isDirect = false,
            )

            synchronized(peerMap) {
                peerMap[advertisedId] = device to System.currentTimeMillis()
                _peers.value = peerMap.values.map { it.first }
                    .sortedBy { it.name }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Invalid beacon: ${e.message}")
        }
    }

    // ── Expiry loop ──────────────────────────────────────────────────

    private suspend fun expiryLoop() {
        while (coroutineContext.isActive) {
            delay(1000)
            val now = System.currentTimeMillis()
            synchronized(peerMap) {
                val expired = peerMap.entries.filter { now - it.value.second > PEER_EXPIRY_MS }
                if (expired.isNotEmpty()) {
                    expired.forEach { peerMap.remove(it.key) }
                    _peers.value = peerMap.values.map { it.first }.sortedBy { it.name }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun buildBeaconPayload(): String {
        return JSONObject().apply {
            put("v", 1)
            put("advertised_id", localAdvertisedId) // NEVER broadcast real device_id.
            put("name", localDeviceName)
            put("plat", "android")
            put("tcp_port", TCP_PORT)
        }.toString()
    }

    /**
     * Derive today's rotating advertised_id via FFI HKDF.
     * Falls back to empty string on error (beacon will be ignored by peers).
     */
    private fun computeAdvertisedId(): String {
        if (localDeviceId.isEmpty()) return ""
        return try {
            val today = LocalDate.now().toString() // e.g. "2026-08-12"
            val json = NxfrService.NxfrBridge.nxfr_advertised_id(localDeviceId, today)
            val result = JSONObject(json)
            result.optString("advertised_id", "")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute advertised_id: ${e.message}")
            ""
        }
    }

    /** Get directed broadcast addresses for all site-local interfaces. */
    private fun getDirectedBroadcasts(): List<String> {
        val broadcasts = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return broadcasts
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    val addr = ifAddr.address ?: continue
                    val bcast = ifAddr.broadcast ?: continue
                    if (addr.isSiteLocalAddress && bcast.hostAddress != null) {
                        broadcasts.add(bcast.hostAddress!!)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get broadcast addresses: ${e.message}")
        }
        return broadcasts.distinct()
    }
}
