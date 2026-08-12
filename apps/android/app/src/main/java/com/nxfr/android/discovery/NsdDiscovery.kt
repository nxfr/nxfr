package com.nxfr.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String
)

class NsdDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "NsdDiscovery"
    }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_nxfr._tcp."
    
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    @Volatile
    private var discovering = false
    
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Discovery started: $regType")
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType == SERVICE_TYPE) {
                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val device = DiscoveredDevice(
                                name = serviceInfo.serviceName,
                                host = serviceInfo.host.hostAddress ?: "",
                                port = serviceInfo.port,
                                deviceId = serviceInfo.attributes["id"]?.let { String(it) } ?: ""
                            )
                            val currentList = _discoveredDevices.value.toMutableList()
                            currentList.add(device)
                            _discoveredDevices.value = currentList
                        }
                    })
                } catch (e: Throwable) {
                    Log.w(TAG, "resolveService failed", e)
                }
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            val currentList = _discoveredDevices.value.toMutableList()
            currentList.removeIf { it.name == service.serviceName }
            _discoveredDevices.value = currentList
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped: $serviceType")
            discovering = false
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed: type=$serviceType code=$errorCode")
            discovering = false
            try { nsdManager.stopServiceDiscovery(this) } catch (_: Throwable) {}
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: type=$serviceType code=$errorCode")
            discovering = false
            try { nsdManager.stopServiceDiscovery(this) } catch (_: Throwable) {}
        }
    }
    
    /** Idempotent: returns early if already discovering. */
    fun startDiscovery() {
        if (discovering) {
            Log.d(TAG, "startDiscovery: already active, skipping")
            return
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discovering = true
            Log.i(TAG, "NSD discovery started")
        } catch (e: Throwable) {
            Log.w(TAG, "discoverServices failed (non-fatal)", e)
            discovering = false
        }
    }
    
    /** Idempotent stop. */
    fun stopDiscovery() {
        if (!discovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Throwable) {
            Log.w(TAG, "stopServiceDiscovery failed (non-fatal)", e)
        }
        discovering = false
    }
    
    fun registerService(port: Int, advertisedId: String, deviceName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "0.1")
            setAttribute("id", advertisedId)
            setAttribute("name", deviceName)
            setAttribute("plat", "android")
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "registerService failed: $errorCode")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "unregisterService failed: $errorCode")
                }
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.d(TAG, "Service unregistered")
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "registerService failed (non-fatal)", e)
        }
    }
}
