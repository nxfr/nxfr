package com.nxfr.android.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface

sealed class P2pState {
    data object Idle : P2pState()
    data object Discovering : P2pState()
    data class PeersFound(val peers: List<P2pPeer>) : P2pState()
    data object Forming : P2pState()
    data class Ready(val isGO: Boolean, val goIp: String, val iface: String) : P2pState()
    data class Failed(val reason: String) : P2pState()
}

data class P2pPeer(
    val deviceAddress: String,  // MAC address for WifiP2pConfig
    val deviceName: String,
    val aid: String,            // advertised_id from DNS-SD TXT record
    val port: Int = 17394
)

class NxfrP2pManager {

    private val _state = MutableStateFlow<P2pState>(P2pState.Idle)
    val state: StateFlow<P2pState> = _state

    private var boundNetwork: Network? = null
    private val _boundIface = MutableStateFlow<String?>(null)
    val boundIface: StateFlow<String?> = _boundIface.asStateFlow()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var localServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var context: Context? = null
    private var localAid: String = ""
    private var localName: String = ""
    private var discoveryJob: Job? = null
    private var connectRetryCount = 0

    private val discoveredPeers = mutableMapOf<String, P2pPeer>()

    companion object {
        private const val TAG = "NxfrP2p"
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        manager = this.context?.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this.context, this.context?.mainLooper, null)

        if (manager == null) {
            Log.e(TAG, "Wi-Fi P2P is not supported by the hardware")
        }
    }

    private fun checkPermission(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                return Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else {
            if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        return null
    }

    fun startDiscovery(localAid: String, localName: String) {
        val missingPermission = checkPermission()
        if (missingPermission != null) {
            _state.value = P2pState.Failed("Permission required: $missingPermission")
            return
        }

        this.localAid = localAid
        this.localName = localName
        _state.value = P2pState.Discovering
        discoveredPeers.clear()

        Log.i(TAG, "[Hop 1/5] Starting P2P discovery: localAid=$localAid localName=$localName")

        setupReceiver()

        val txtRecord = mapOf("aid" to localAid, "name" to localName, "port" to "17394")
        localServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(localName, "_nxfr._tcp", txtRecord)
        
        addLocalService()

        manager?.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, device ->
                Log.d(TAG, "DNS-SD Service Response: $instanceName, $registrationType, ${device.deviceName}")
            },
            { fullDomainName, txtRecordMap, device ->
                Log.d(TAG, "DNS-SD TXT Record Response: $fullDomainName, $txtRecordMap")
                val aid = txtRecordMap["aid"]
                val name = txtRecordMap["name"] ?: device.deviceName
                val portStr = txtRecordMap["port"]
                val port = portStr?.toIntOrNull() ?: 17394

                if (aid != null && aid != this.localAid) {
                    val peer = P2pPeer(device.deviceAddress, name, aid, port)
                    discoveredPeers[device.deviceAddress] = peer
                    Log.i(TAG, "[Hop 2/5] Discovered P2P peer via DNS-SD: ${peer.deviceName} (${peer.deviceAddress}, aid=${peer.aid})")
                    _state.value = P2pState.PeersFound(discoveredPeers.values.toList())
                }
            }
        )

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        addServiceRequestAndDiscover()

        // Also start peer discovery immediately to prime P2P table across all Android chipsets
        discoverPeersFallback()

        discoveryJob = scope.launch {
            delay(5000)
            if (_state.value is P2pState.Discovering && discoveredPeers.isEmpty()) {
                Log.i(TAG, "Retrying peer discovery sweep...")
                discoverPeersFallback()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addLocalService() {
        manager?.addLocalService(channel, localServiceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Local service added successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to add local service, reason: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun addServiceRequestAndDiscover() {
        manager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Service discovery started successfully")
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to start service discovery, reason: $reason — falling back to peer discovery")
                        discoverPeersFallback()
                    }
                })
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to add service request, reason: $reason — falling back to peer discovery")
                discoverPeersFallback()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeersFallback() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery active")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to start peer discovery, reason: $reason")
                if (_state.value is P2pState.Discovering && discoveredPeers.isEmpty()) {
                    val msg = when (reason) {
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Toggle Wi-Fi and retry."
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported on this device."
                        else -> "Discovery error ($reason). Ensure Wi-Fi & Location are enabled."
                    }
                    _state.value = P2pState.Failed(msg)
                }
            }
        })
    }

    private fun setupReceiver() {
        receiver?.let {
            try { context?.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            _state.value = P2pState.Failed("Wi-Fi Direct not available (Wi-Fi is turned off)")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (checkPermission() == null) {
                            manager?.requestPeers(channel) { peers ->
                                val list = peers?.deviceList ?: return@requestPeers
                                val validPeers = list.map { device ->
                                    val name = if (device.deviceName.isNullOrBlank()) {
                                        "Station ${device.deviceAddress.takeLast(5)}"
                                    } else {
                                        device.deviceName
                                    }
                                    P2pPeer(device.deviceAddress, name, "p2p_direct")
                                }
                                
                                if (validPeers.isNotEmpty()) {
                                    validPeers.forEach { discoveredPeers[it.deviceAddress] = it }
                                    _state.value = P2pState.PeersFound(discoveredPeers.values.toList())
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            manager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                                val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                val isGO = info.isGroupOwner
                                val iface = findP2pInterface() ?: "p2p0"

                                Log.i(TAG, "[Hop 4/5] P2P group formed: isGO=$isGO, goIp=$goIp, iface=$iface")

                                scope.launch(Dispatchers.IO) {
                                    // 1. Group Owner: ensure listener is active on TCP 17394 BEFORE client connects
                                    if (isGO) {
                                        ensureGoListenerStarted()
                                    }

                                    // 2. Find WFD network and bind process so all sockets egress over P2P interface
                                    val boundIfaceName = bindToWfdNetworkSync(iface)

                                    // 3. Transition to Ready state once process network is bound
                                    _state.value = P2pState.Ready(isGO = isGO, goIp = goIp, iface = boundIfaceName)
                                    connectRetryCount = 0
                                    Log.i(TAG, "[Hop 5/5] Desert Mode READY: role=${if (isGO) "GROUP_OWNER" else "CLIENT"}, goIp=$goIp, routedIface=$boundIfaceName")
                                }
                            }
                        } else {
                            unbindWfdNetwork()
                            if (_state.value is P2pState.Ready || _state.value is P2pState.Forming) {
                                _state.value = P2pState.Idle
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Log.i(TAG, "This device changed: ${device?.deviceName}")
                    }
                }
            }
        }

        // Wi-Fi P2P intents are system broadcasts — must use RECEIVER_EXPORTED on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context?.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            context?.registerReceiver(receiver, intentFilter)
        }
    }

    private suspend fun bindToWfdNetworkSync(ifaceFallback: String): String {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ifaceFallback

        Log.i(TAG, "[Hop 4a/5] Searching for WFD network in ConnectivityManager...")

        for (attempt in 1..20) {
            val networks = cm.allNetworks
            var targetNet: Network? = null
            var targetIface: String? = null

            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net)
                val lp = cm.getLinkProperties(net)
                val ifName = lp?.interfaceName

                val isWfdTransport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true
                } else false

                val isP2pIface = ifName != null && (ifName.startsWith("p2p") || ifName.contains("p2p"))
                val hasP2pRoute = lp?.routes?.any { route ->
                    route.destination?.address?.hostAddress?.startsWith("192.168.49.") == true ||
                    route.gateway?.hostAddress?.startsWith("192.168.49.") == true
                } == true

                if (isWfdTransport || isP2pIface || hasP2pRoute) {
                    targetNet = net
                    targetIface = ifName ?: findP2pInterface() ?: ifaceFallback
                    break
                }
            }

            if (targetNet != null) {
                try {
                    cm.bindProcessToNetwork(targetNet)
                    boundNetwork = targetNet
                    _boundIface.value = targetIface
                    Log.i(TAG, "[Hop 4b/5] Bound process to WFD network: $targetNet, iface=$targetIface")
                    return targetIface ?: ifaceFallback
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind process to WFD network on attempt $attempt", e)
                }
            }
            delay(100)
        }

        val fallbackIface = findP2pInterface() ?: ifaceFallback
        _boundIface.value = fallbackIface
        Log.w(TAG, "No distinct WFD network found in ConnectivityManager; using fallback iface: $fallbackIface")
        return fallbackIface
    }

    fun unbindWfdNetwork() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (boundNetwork != null || _boundIface.value != null) {
            try {
                cm?.bindProcessToNetwork(null)
                Log.i(TAG, "Unbound process from WFD network (previously ${_boundIface.value}). Normal routing restored.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding process from network", e)
            }
            boundNetwork = null
            _boundIface.value = null
        }
    }

    private suspend fun ensureGoListenerStarted() {
        context?.let { ctx ->
            try {
                if (!NxfrService.isListening.value) {
                    Log.i(TAG, "[Hop 4c/5] Group Owner starting TCP 17394 listener...")
                    val intent = Intent(ctx, NxfrService::class.java)
                    ctx.startService(intent)
                    NxfrService.startListening(ctx)
                    delay(300) // Allow listener socket to bind and enter accept loop
                    Log.i(TAG, "[Hop 4d/5] Group Owner TCP 17394 listener is ready.")
                } else {
                    Log.i(TAG, "[Hop 4c/5] Group Owner TCP 17394 listener is already active.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting GO listener", e)
            }
        }
    }

    fun connect(peer: P2pPeer) {
        val missingPermission = checkPermission()
        if (missingPermission != null) {
            _state.value = P2pState.Failed("Permission required: $missingPermission")
            return
        }

        _state.value = P2pState.Forming
        val config = WifiP2pConfig().apply { 
            deviceAddress = peer.deviceAddress 
        }

        connectInternal(config, peer)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(config: WifiP2pConfig, peer: P2pPeer) {
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "[Hop 3/5] P2P connection initiated to ${peer.deviceName} (${peer.deviceAddress})")
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && connectRetryCount < 2) {
                    connectRetryCount++
                    Log.i(TAG, "Connection busy, retrying... ($connectRetryCount/2)")
                    scope.launch {
                        delay(500)
                        connectInternal(config, peer)
                    }
                } else if (connectRetryCount >= 2) {
                    _state.value = P2pState.Failed("Connection failed after 2 retries")
                } else {
                    _state.value = P2pState.Failed("Connection failed, reason: $reason")
                }
            }
        })
    }

    private fun findP2pInterface(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isUp) {
                    val name = intf.name
                    if (name.startsWith("p2p") || name.contains("-p2p") || name.contains("p2p-")) {
                        return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding P2P interface", e)
        }
        return null
    }

    fun teardown() {
        discoveryJob?.cancel()
        unbindWfdNetwork()

        receiver?.let { 
            try {
                context?.unregisterReceiver(it) 
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
        receiver = null
        
        localServiceInfo?.let { 
            manager?.removeLocalService(channel, it, null) 
        }
        localServiceInfo = null
        
        serviceRequest?.let { 
            manager?.removeServiceRequest(channel, it, null) 
        }
        serviceRequest = null

        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group removed successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove group, reason: $reason")
            }
        })

        _state.value = P2pState.Idle
        connectRetryCount = 0
        Log.i(TAG, "P2P teardown complete")
    }

    fun cancelDiscoveryOnly() {
        discoveryJob?.cancel()
        unbindWfdNetwork()
        
        manager?.stopPeerDiscovery(channel, null)
        
        serviceRequest?.let { 
            manager?.removeServiceRequest(channel, it, null) 
        }
        serviceRequest = null
        
        receiver?.let { 
            try {
                context?.unregisterReceiver(it) 
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
        receiver = null
        
        _state.value = P2pState.Idle
        Log.i(TAG, "P2P discovery cancelled")
    }
}
