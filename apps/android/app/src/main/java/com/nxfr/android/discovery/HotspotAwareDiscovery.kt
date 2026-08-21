package com.nxfr.android.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 4-tier "discovery ladder" for hotspot-resilient device discovery:
 *
 * Tier 0 — UDP BEACON (instant, works on hotspots, every 1s)
 * Tier 1 — NSD (mDNS/DNS-SD via NsdDiscovery)
 * Tier 2 — TCP PROBE FALLBACK (scan local /24 subnets on port 17394)
 * Tier 3 — MANUAL (handled by UI)
 */
class HotspotAwareDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "HotspotDiscovery"
        private const val NXFR_PORT = 17394
        private const val PROBE_TIMEOUT_NORMAL_MS = 300
        private const val PROBE_TIMEOUT_CONGESTED_MS = 750
        private const val BEACON_CONGESTION_THRESHOLD = 0.5f
        private const val NSD_TIMEOUT_MS = 3000L
        private const val MAX_CONCURRENT_PROBES = 32
    }

    private val nsdDiscovery = NsdDiscovery(context)
    private val beacon = UdpBeacon(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _devices = MutableStateFlow<List<DeviceUiModel>>(emptyList())
    val devices: StateFlow<List<DeviceUiModel>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _showHotspotBanner = MutableStateFlow(false)
    val showHotspotBanner: StateFlow<Boolean> = _showHotspotBanner.asStateFlow()

    private var nsdJob: Job? = null
    private var probeJob: Job? = null
    private var beaconJob: Job? = null
    private var storeDir: String = ""

    // Adaptive probe: track beacon reception rate.
    private var beaconStartTimeMs: Long = 0L
    @Volatile private var beaconsReceived: Int = 0

    /** Compute adaptive probe timeout based on beacon success rate. */
    private fun adaptiveProbeTimeoutMs(): Int {
        val elapsed = System.currentTimeMillis() - beaconStartTimeMs
        val expectedBeacons = (elapsed / 1000).coerceAtLeast(1) // 1 beacon/s
        val rate = beaconsReceived.toFloat() / expectedBeacons.toFloat()
        val timeout = if (rate < BEACON_CONGESTION_THRESHOLD) {
            PROBE_TIMEOUT_CONGESTED_MS
        } else {
            PROBE_TIMEOUT_NORMAL_MS
        }
        Log.d(TAG, "Adaptive timeout: rate=${"%,.2f".format(rate)} (${beaconsReceived}/${expectedBeacons}) → ${timeout}ms")
        return timeout
    }

    /**
     * Start all tiers: Tier 0 (beacon), Tier 1 (NSD), Tier 2 fallback after 3 s.
     */
    fun startDiscovery(
        storeDir: String,
        localDeviceId: String = "",
        localDeviceName: String = "NXFR-Android",
        localPort: Int = NXFR_PORT
    ) {
        this.storeDir = storeDir
        _isScanning.value = true
        _showHotspotBanner.value = isOnHotspot()

        // Tier 0: UDP beacon (instant).
        beacon.localDeviceId = localDeviceId
        beacon.localDeviceName = localDeviceName
        beacon.localPort = localPort
        beacon.start()
        beaconStartTimeMs = System.currentTimeMillis()
        beaconsReceived = 0

        beaconJob = scope.launch {
            beacon.peers.collect { beaconPeers ->
                beaconsReceived = beaconPeers.size.coerceAtLeast(beaconsReceived)
                mergeDevices(beaconPeers)
            }
        }

        // Tier 1: NSD (non-fatal — wrapped so NSD failure degrades to beacon + probe).
        try {
            nsdDiscovery.startDiscovery()
        } catch (e: Throwable) {
            Log.w(TAG, "NSD tier failed (non-fatal, beacon+probe still active)", e)
        }

        nsdJob = scope.launch {
            // Collect NSD results.
            launch {
                nsdDiscovery.discoveredDevices.collect { nsdDevices ->
                    mergeDevices(nsdDevices.map { d ->
                        DeviceUiModel(
                            name = d.name,
                            host = d.host,
                            port = d.port,
                            deviceId = d.deviceId,
                            platform = "unknown",
                            isPaired = false,
                            isDirect = false,
                        )
                    })
                }
            }

            // Tier 2 fallback: wait 3 s, if no NSD results, probe.
            delay(NSD_TIMEOUT_MS)
            if (_devices.value.isEmpty()) {
                Log.i(TAG, "No peers after ${NSD_TIMEOUT_MS}ms, starting TCP probe")
                runProbe()
            }
            _isScanning.value = false
        }
    }

    /** Stop all discovery. */
    fun stopDiscovery() {
        nsdJob?.cancel()
        probeJob?.cancel()
        beaconJob?.cancel()
        nsdDiscovery.stopDiscovery()
        beacon.stop()
        _isScanning.value = false
        _isProbing.value = false
    }

    /** Force Tier 2 probe (pull-to-refresh / manual). */
    fun refreshProbe() {
        probeJob?.cancel()
        probeJob = scope.launch { runProbe() }
    }

    /** Set the UDP beacon mode (ACTIVE: 1s, BACKGROUND: 5s, LOW_POWER: 30s). */
    fun setBeaconMode(mode: UdpBeacon.BeaconMode) {
        beacon.setBeaconMode(mode)
    }

    /** Dismiss the hotspot banner. */
    fun dismissBanner() {
        _showHotspotBanner.value = false
    }

    /** Tier 2: TCP probe all local /24 subnets. */
    private suspend fun runProbe() {
        _isProbing.value = true
        val subnets = getLocalSubnets()
        Log.i(TAG, "Probing ${subnets.size} subnet(s): $subnets")

        val found = mutableListOf<DeviceUiModel>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_PROBES)

        coroutineScope {
            for (subnet in subnets) {
                for (host in 1..254) {
                    launch {
                        semaphore.acquire()
                        try {
                            val ip = "${subnet.prefix}.$host"
                            if (ip == subnet.localIp) return@launch // Skip self.
                            val device = probeHost(ip, NXFR_PORT)
                            if (device != null) {
                                synchronized(found) { found.add(device) }
                                mergeDevices(found.toList())
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }
        }

        _isProbing.value = false
        Log.i(TAG, "Probe complete: found ${found.size} device(s)")
    }

    /** TCP connect to host:port with adaptive timeout, then TLS+HELLO via FFI. */
    private fun probeHost(ip: String, port: Int): DeviceUiModel? {
        return try {
            // Quick TCP connect test with adaptive timeout.
            val timeout = adaptiveProbeTimeoutMs()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), timeout)
            }
            // TCP open — try full TLS+HELLO via FFI.
            if (storeDir.isEmpty()) return null
            val connJson = NxfrService.NxfrBridge.nxfr_connect("$ip:$port", storeDir)
            val result = JSONObject(connJson)
            if (result.has("error")) {
                Log.d(TAG, "Probe $ip:$port TLS failed: ${result.getString("error")}")
                return null
            }
            val handle = result.getLong("handle")
            val peerName = result.optString("peer_name", ip)
            val peerDeviceId = result.optString("peer_device_id", "")

            // Close immediately after identification.
            try { NxfrService.NxfrBridge.nxfr_close(handle) } catch (_: Throwable) {}

            Log.i(TAG, "Probe $ip:$port found: $peerName")
            DeviceUiModel(
                name = peerName,
                host = ip,
                port = port,
                deviceId = peerDeviceId,
                platform = "linux", // Assume Linux for now; could parse from HELLO.
                isPaired = false,
                isDirect = true,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Merge discovered devices (dedup by device_id, fallback host:port). */
    private fun mergeDevices(newDevices: List<DeviceUiModel>) {
        val current = _devices.value.toMutableList()
        for (device in newDevices) {
            val existing = current.indexOfFirst { existing ->
                (existing.deviceId.isNotEmpty() && existing.deviceId == device.deviceId) ||
                (existing.host == device.host && existing.port == device.port)
            }
            if (existing >= 0) {
                current[existing] = device
            } else {
                current.add(device)
            }
        }
        // Sort: paired first, then direct, then alphabetical.
        _devices.value = current.sortedWith(
            compareByDescending<DeviceUiModel> { it.isPaired }
                .thenByDescending { it.isDirect }
                .thenBy { it.name }
        )
    }

    /** Detect hotspot topology: WiFi OFF + mobile data ON. */
    private fun isOnHotspot(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return hasCellular && !hasWifi
    }

    /** Get local /24 subnets from network interfaces. */
    private fun getLocalSubnets(): List<SubnetInfo> {
        val subnets = mutableListOf<SubnetInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return subnets
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is InetAddress && addr.isSiteLocalAddress && addr.hostAddress != null) {
                        val ip = addr.hostAddress!!
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            subnets.add(SubnetInfo(
                                prefix = "${parts[0]}.${parts[1]}.${parts[2]}",
                                localIp = ip,
                                interfaceName = iface.name,
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate network interfaces", e)
        }
        return subnets.distinctBy { it.prefix }
    }

    private data class SubnetInfo(
        val prefix: String,
        val localIp: String,
        val interfaceName: String,
    )
}

/** UI-facing device model used by SendScreen. */
data class DeviceUiModel(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val platform: String = "unknown",
    val isPaired: Boolean = false,
    val isDirect: Boolean = false,
)
