package com.nxfr.android.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.net.*

/**
 * UDP beacon discovery — LocalSend-style instant device finding.
 *
 * Tier 0 in the discovery ladder: fastest path, works on hotspots
 * where mDNS is blocked.
 *
 * Protocol: every 1 s, broadcast a small JSON datagram on port 17395
 * to all site-local directed broadcast addresses + multicast 224.0.0.251.
 * Peers do the same; we listen on 0.0.0.0:17395.
 *
 * Beacon format (JSON, < 256 bytes):
 *   {"v":1,"device_id":"hex","name":"My Phone","plat":"android","tcp_port":17394}
 */
class UdpBeacon(private val context: Context) {
    companion object {
        private const val TAG = "UdpBeacon"
        const val BEACON_PORT = 17395
        const val TCP_PORT = 17394
        private const val ANNOUNCE_INTERVAL_MS = 1000L
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

    // device_id → (DeviceUiModel, lastSeenMs)
    private val peerMap = mutableMapOf<String, Pair<DeviceUiModel, Long>>()

    var localDeviceId: String = ""
    var localDeviceName: String = "NXFR-Android"

    /** Start announcing + listening. */
    fun start() {
        if (listenJob?.isActive == true) return
        // Launch all I/O off the calling thread.
        scope.launch {
            try {
                Log.i(TAG, "Starting UDP beacon on port $BEACON_PORT")
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
            delay(ANNOUNCE_INTERVAL_MS)
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

            val deviceId = obj.optString("device_id", "")
            if (deviceId.isEmpty() || deviceId == localDeviceId) return // Ignore self.

            val name = obj.optString("name", "Unknown")
            val plat = obj.optString("plat", "unknown")
            val tcpPort = obj.optInt("tcp_port", TCP_PORT)
            val host = packet.address.hostAddress ?: return

            val device = DeviceUiModel(
                name = name,
                host = host,
                port = tcpPort,
                deviceId = deviceId,
                platform = plat,
                isPaired = false,
                isDirect = false,
            )

            synchronized(peerMap) {
                peerMap[deviceId] = device to System.currentTimeMillis()
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
            put("device_id", localDeviceId)
            put("name", localDeviceName)
            put("plat", "android")
            put("tcp_port", TCP_PORT)
        }.toString()
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
