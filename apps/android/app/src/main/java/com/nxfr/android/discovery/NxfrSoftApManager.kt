package com.nxfr.android.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class SoftApState {
    data object Idle : SoftApState()
    data object Starting : SoftApState()
    data class Active(
        val ssid: String,
        val passphrase: String?,  // null on OEMs that don't expose it
        val hostIp: String
    ) : SoftApState()
    data class Failed(val reason: String) : SoftApState()
}

sealed class ClientJoinState {
    data object Idle : ClientJoinState()
    data object Connecting : ClientJoinState()
    data class Connected(val hostIp: String) : ClientJoinState()
    data class Failed(val reason: String) : ClientJoinState()
}

class NxfrSoftApManager {
    companion object {
        private const val TAG = "NxfrSoftAp"
    }

    private val _hostState = MutableStateFlow<SoftApState>(SoftApState.Idle)
    val hostState: StateFlow<SoftApState> = _hostState
    private val _clientState = MutableStateFlow<ClientJoinState>(ClientJoinState.Idle)
    val clientState: StateFlow<ClientJoinState> = _clientState
    
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun startHotspot() {
        if (context == null) {
            _hostState.value = SoftApState.Failed("Context is null")
            return
        }
        
        _hostState.value = SoftApState.Starting
        val wifiManager = context!!.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    this@NxfrSoftApManager.reservation = reservation
                    
                    // API 30+: read from SoftApConfiguration
                    // API 26-29: read from WifiConfiguration (deprecated but functional)
                    val (ssid, passphrase) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        val ssid = config.ssid ?: "NXFR-AP"
                        val passphrase = config.passphrase  // may be null for open networks
                        ssid to passphrase
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation.wifiConfiguration
                        val ssid = config?.SSID ?: "NXFR-AP"
                        val passphrase = config?.preSharedKey
                        ssid to passphrase
                    }
                    
                    // Find host IP on AP interface
                    val hostIp = findApInterfaceIp() ?: "192.168.43.1"  // common default
                    
                    _hostState.value = SoftApState.Active(
                        ssid = ssid,
                        passphrase = passphrase,
                        hostIp = hostIp
                    )
                    Log.i(TAG, "Hotspot active: SSID=$ssid hostIp=$hostIp")
                }
                
                override fun onStopped() {
                    this@NxfrSoftApManager.reservation = null
                    _hostState.value = SoftApState.Idle
                    Log.i(TAG, "Hotspot stopped")
                }
                
                override fun onFailed(reason: Int) {
                    val msg = when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "Hotspot failed. Ensure Wi-Fi & Location are enabled in Quick Settings."
                        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Hotspot already active or Wi-Fi Direct in use."
                        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspot tethering disallowed by system policy."
                        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No available Wi-Fi channel."
                        else -> "Hotspot failed (code $reason)."
                    }
                    _hostState.value = SoftApState.Failed(msg)
                    Log.e(TAG, msg)
                }
            }, handler)
        } catch (e: SecurityException) {
            _hostState.value = SoftApState.Failed("Permission denied: Turn ON Location & Wi-Fi in Quick Settings.")
            Log.e(TAG, "SecurityException starting hotspot", e)
        } catch (t: Throwable) {
            _hostState.value = SoftApState.Failed(t.message ?: "Failed to start hotspot")
            Log.e(TAG, "Throwable starting hotspot", t)
        }
    }

    fun resetState() {
        _hostState.value = SoftApState.Idle
        _clientState.value = ClientJoinState.Idle
    }

    fun stopHotspot() {
        try {
            reservation?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Hotspot close error: ${e.message}")
        }
        reservation = null
        _hostState.value = SoftApState.Idle
    }

    fun joinNetwork(ssid: String, passphrase: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _clientState.value = ClientJoinState.Connecting
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply {
                    if (passphrase != null) {
                        setWpa2Passphrase(passphrase)
                    }
                }
                .build()
            
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            
            val cm = context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Bind process to this network so TCP connections go over SoftAP
                    cm.bindProcessToNetwork(network)
                    
                    // Find the gateway/host IP (typically .1 on the AP subnet)
                    val hostIp = findApHostIp(network, cm) ?: "192.168.43.1"
                    _clientState.value = ClientJoinState.Connected(hostIp)
                    Log.i(TAG, "Joined SoftAP network, hostIp=$hostIp")
                }
                
                override fun onUnavailable() {
                    _clientState.value = ClientJoinState.Failed("Network request denied or timed out")
                    Log.e(TAG, "SoftAP join failed: unavailable")
                }
                
                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)  // Unbind
                    _clientState.value = ClientJoinState.Failed("SoftAP network lost")
                }
            }
            
            cm.requestNetwork(request, networkCallback!!, handler, 30_000)  // 30s timeout
        } else {
            // API 26-28: Cannot programmatically join. Show credentials for manual join.
            _clientState.value = ClientJoinState.Failed("Manual Wi-Fi join required on Android ${Build.VERSION.SDK_INT}")
        }
    }

    fun leaveNetwork() {
        networkCallback?.let { cb ->
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try {
                cm?.unregisterNetworkCallback(cb)
                cm?.bindProcessToNetwork(null)
            } catch (e: Throwable) {
                Log.w(TAG, "Network callback unregister error: ${e.message}")
            }
        }
        networkCallback = null
        _clientState.value = ClientJoinState.Idle
    }

    private fun findApInterfaceIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                // Common AP interface names
                if (name.startsWith("ap") || name.startsWith("swlan") || 
                    name.startsWith("wlan") && name.contains("ap")) {
                    for (addr in iface.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            Log.d(TAG, "Found AP IP: ${addr.hostAddress} on $name")
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find AP interface IP", e)
        }
        return null
    }

    private fun findApHostIp(network: Network, cm: ConnectivityManager): String? {
        try {
            val linkProps = cm.getLinkProperties(network) ?: return null
            val gateway = linkProps.routes
                .firstOrNull { it.isDefaultRoute }
                ?.gateway
            return (gateway as? java.net.Inet4Address)?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find AP host IP", e)
        }
        return null
    }

    fun teardown() {
        stopHotspot()
        leaveNetwork()
        Log.i(TAG, "SoftAP teardown complete")
    }
}
