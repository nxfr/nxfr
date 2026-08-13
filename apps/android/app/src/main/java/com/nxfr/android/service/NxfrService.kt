package com.nxfr.android.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nxfr.android.NxfrApp
import com.nxfr.android.R
import com.nxfr.android.discovery.HotspotAwareDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class NxfrState {
    data object Idle : NxfrState()
    data object Listening : NxfrState()
    data class ManualConnected(
        val addr: String,
        val peerName: String,
        val handle: Long
    ) : NxfrState()
    data class Offering(
        val handle: Long,
        val displayName: String,
        val totalSize: Long,
        val totalFiles: Int,
        val peerName: String
    ) : NxfrState()
    data class Transferring(
        val progress: Float,
        val total: Long,
        val fileName: String,
        val isSending: Boolean
    ) : NxfrState()
    data class Complete(val filePath: String?) : NxfrState()
    data class Error(val msg: String) : NxfrState()
}

class NxfrService : Service() {

    companion object {
        private const val TAG = "NxfrService"
        const val ACTION_SEND = "com.nxfr.android.SEND"
        const val ACTION_CONNECT = "com.nxfr.android.CONNECT"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_FILE_PATH = "file_path"
        const val DEFAULT_PORT = 17394
        const val DEFAULT_MULTICAST = "224.0.0.251"

        init {
            System.loadLibrary("nxfr_ffi")
        }

        /** Shared identity state, readable from any Activity/Composable. */
        private val _deviceId = MutableStateFlow("")
        val deviceId: StateFlow<String> = _deviceId.asStateFlow()

        private val _deviceName = MutableStateFlow("My Device")
        val deviceName: StateFlow<String> = _deviceName.asStateFlow()

        private val _nxfrState = MutableStateFlow<NxfrState>(NxfrState.Idle)
        val nxfrState: StateFlow<NxfrState> = _nxfrState.asStateFlow()

        fun setDeviceName(name: String) { _deviceName.value = name }

        /** Service-owned discovery — shared across all tabs. */
        private var _discovery: HotspotAwareDiscovery? = null
        val discovery: HotspotAwareDiscovery? get() = _discovery

        @Volatile var instance: NxfrService? = null

        private val _isListening = MutableStateFlow(false)
        val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

        /** Cancel the active transfer from any context. */
        fun cancelActiveTransfer() {
            instance?.let { svc ->
                val handle = svc.activeSessionHandle
                if (handle != 0L) {
                    try { NxfrBridge.nxfr_close(handle) } catch (_: Throwable) {}
                    svc.activeSessionHandle = 0
                }
                _nxfrState.value = NxfrState.Idle
                Log.i("NxfrService", "Active transfer cancelled")
            }
        }
    }

    /**
     * JNI bridge to libnxfr_ffi.so.
     * All protocol logic (CBOR, framing, TLS) stays in Rust.
     * Kotlin only passes strings/handles and receives JSON events.
     *
     * Phase 7: nxfr_connect / nxfr_listen now take storeDir (path to
     * identity directory) instead of identityJson.
     */
    object NxfrBridge {
        // ── Identity ───────────────────────────────────────
        external fun nxfr_identity_generate(storeDir: String): String
        external fun nxfr_identity_load(storeDir: String): String

        // ── Connection ─────────────────────────────────────
        external fun nxfr_connect(addr: String, storeDir: String): String
        external fun nxfr_listen(port: Int, storeDir: String): String
        external fun nxfr_accept(listener: Long): String

        // ── Transfer ───────────────────────────────────────
        external fun nxfr_send_file(handle: Long, path: String): String
        external fun nxfr_pump(handle: Long): String
        external fun nxfr_confirm(handle: Long, accepted: Boolean): String
        external fun nxfr_close(handle: Long): String

        // ── Pairing ────────────────────────────────────────
        external fun nxfr_pair_begin(handle: Long): String
        external fun nxfr_pair_confirm(handle: Long, accepted: Boolean, storeDir: String): String
        external fun nxfr_paired_list(storeDir: String): String
        external fun nxfr_unpair(storeDir: String, deviceId: String): String
        external fun nxfr_set_auto_accept(storeDir: String, deviceId: String, policy: String): String
        external fun nxfr_set_name(storeDir: String, name: String): String

        // ── Utilities ──────────────────────────────────────
        external fun nxfr_sanitize_path(path: String): String
        external fun nxfr_sha256(data: ByteArray): String
        external fun nxfr_advertised_id(deviceIdHex: String, dateStr: String): String
        external fun nxfr_derive_sas(
            deviceIdAHex: String,
            deviceIdBHex: String,
            exporterBytes: ByteArray
        ): String

        external fun nxfr_string_free(ptr: Long)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerHandle: Long = 0
    private var activeSessionHandle: Long = 0
    @Volatile private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashCatcher()
        loadOrGenerateIdentity()
        _discovery = HotspotAwareDiscovery(this)
    }

    /** Write crash stack to filesDir before rethrowing — survives logcat rotation. */
    private fun installCrashCatcher() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = System.currentTimeMillis()
                val crashFile = java.io.File(filesDir, "crash-$ts.log")
                crashFile.writeText("Thread: ${thread.name}\n${android.util.Log.getStackTraceString(throwable)}")
                Log.e(TAG, "Crash logged to ${crashFile.absolutePath}")
            } catch (_: Throwable) { /* best effort */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Load identity from filesDir/nxfr-identity/. If missing or error,
     * generate a new one. Pushes the real device_id into the companion
     * StateFlow so the UI can observe it.
     */
    private fun loadOrGenerateIdentity() {
        val storeDir = identityDir()
        Log.i(TAG, "Identity storeDir: $storeDir")

        // Try loading first.
        val loadJson = NxfrBridge.nxfr_identity_load(storeDir)
        val loadResult = JSONObject(loadJson)
        Log.d(TAG, "identity_load result: $loadJson")

        if (!loadResult.has("error") && loadResult.optString("device_id").isNotEmpty()) {
            val id = loadResult.getString("device_id")
            _deviceId.value = id
            Log.i(TAG, "Loaded identity: device_id=$id")
            return
        }

        // Generate if load failed.
        Log.i(TAG, "No existing identity, generating...")
        val genJson = NxfrBridge.nxfr_identity_generate(storeDir)
        val genResult = JSONObject(genJson)
        Log.d(TAG, "identity_generate result: $genJson")

        if (!genResult.has("error") && genResult.optString("device_id").isNotEmpty()) {
            val id = genResult.getString("device_id")
            _deviceId.value = id
            Log.i(TAG, "Generated identity: device_id=$id")
        } else {
            Log.e(TAG, "Failed to generate identity: $genJson")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()
        when (intent?.action) {
            ACTION_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: return START_STICKY
                serviceScope.launch { doManualConnect(addr) }
            }
            ACTION_SEND -> {
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: return START_STICKY
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_STICKY
                serviceScope.launch { doSendFile(addr, filePath) }
            }
            else -> {
                // Reconcile: if visible was persisted ON, start listening.
                val prefs = getSharedPreferences("nxfr_prefs", MODE_PRIVATE)
                val visibleOn = prefs.getBoolean("visible_enabled", false)
                if (visibleOn || intent?.action == null) {
                    serviceScope.launch { startListening() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        _isListening.value = false
        _discovery?.stopDiscovery()
        _discovery = null
        super.onDestroy()
        serviceScope.cancel()
        if (activeSessionHandle != 0L) {
            NxfrBridge.nxfr_close(activeSessionHandle)
            activeSessionHandle = 0
        }
    }

    /** Accept loop: listen → accept → pump for offers. */
    private suspend fun startListening() {
        if (listening) {
            Log.w(TAG, "startListening: already active, skipping")
            return
        }
        listening = true
        val storeDir = identityDir()

        // Start beacon announcer so this device is discoverable.
        val did = _deviceId.value
        val dname = _deviceName.value
        if (did.isNotEmpty()) {
            _discovery?.startDiscovery(storeDir, did, dname)
            Log.i(TAG, "Beacon announcer started (Visible=ON)")
        }

        var listenJson = withContext(Dispatchers.IO) {
            NxfrBridge.nxfr_listen(DEFAULT_PORT, storeDir)
        }
        var listenResult = JSONObject(listenJson)

        // EADDRINUSE retry: close stale listener, try once more.
        if (listenResult.has("error") && listenResult.getString("error").contains("bind", ignoreCase = true)) {
            Log.w(TAG, "Bind failed (EADDRINUSE?), closing stale listener and retrying")
            if (listenerHandle != 0L) {
                try { withContext(Dispatchers.IO) { NxfrBridge.nxfr_close(listenerHandle) } } catch (_: Throwable) {}
                listenerHandle = 0
            }
            delay(500)
            listenJson = withContext(Dispatchers.IO) {
                NxfrBridge.nxfr_listen(DEFAULT_PORT, storeDir)
            }
            listenResult = JSONObject(listenJson)
        }

        if (listenResult.has("error")) {
            val msg = listenResult.getString("error")
            Log.e(TAG, "Listen failed: $msg")
            _nxfrState.value = NxfrState.Error(msg)
            _isListening.value = false
            listening = false
            return
        }
        listenerHandle = listenResult.getLong("listener")
        _nxfrState.value = NxfrState.Listening
        Log.i(TAG, "Listening on port ${listenResult.getInt("port")}")
        _isListening.value = true

        // Accept loop.
        while (isActive) {
            val acceptJson = withContext(Dispatchers.IO) {
                NxfrBridge.nxfr_accept(listenerHandle)
            }
            val acceptResult = JSONObject(acceptJson)
            if (acceptResult.has("error")) {
                Log.e(TAG, "Accept error: ${acceptResult.getString("error")}")
                continue
            }
            val handle = acceptResult.getLong("handle")
            activeSessionHandle = handle
            Log.i(TAG, "Accepted connection from ${acceptResult.optString("peer_name")}")

            // Pump for events on this session.
            pumpLoop(handle, isSending = false)
        }
    }

    /** Connect to a remote device without sending a file yet. */
    private suspend fun doManualConnect(addr: String) {
        try {
            val storeDir = identityDir()
            Log.i(TAG, "Manual connect to $addr")

            val connJson = withContext(Dispatchers.IO) {
                NxfrBridge.nxfr_connect(addr, storeDir)
            }
            val connResult = JSONObject(connJson)
            if (connResult.has("error")) {
                val msg = connResult.getString("error")
                Log.e(TAG, "Manual connect failed: $msg")
                _nxfrState.value = NxfrState.Error(msg)
                return
            }
            val handle = connResult.getLong("handle")
            val peerName = connResult.optString("peer_name", addr)
            activeSessionHandle = handle
            Log.i(TAG, "Manual connected to $addr, peer=$peerName, handle=$handle")
            _nxfrState.value = NxfrState.ManualConnected(addr, peerName, handle)
        } catch (e: Throwable) {
            val msg = e.message ?: "Unknown connect error"
            Log.e(TAG, "Manual connect exception: $msg", e)
            _nxfrState.value = NxfrState.Error(msg)
        }
    }

    /** Send a file to a remote device. */
    private suspend fun doSendFile(addr: String, filePath: String) {
        val storeDir = identityDir()
        // Identity already loaded in onCreate.

        // Connect.
        val connJson = withContext(Dispatchers.IO) {
            NxfrBridge.nxfr_connect(addr, storeDir)
        }
        val connResult = JSONObject(connJson)
        if (connResult.has("error")) {
            _nxfrState.value = NxfrState.Error(connResult.getString("error"))
            return
        }
        val handle = connResult.getLong("handle")
        activeSessionHandle = handle
        Log.i(TAG, "Connected to $addr, peer=${connResult.optString("peer_name")}")

        // Send file.
        val sendJson = withContext(Dispatchers.IO) {
            NxfrBridge.nxfr_send_file(handle, filePath)
        }
        val sendResult = JSONObject(sendJson)
        if (sendResult.has("error")) {
            _nxfrState.value = NxfrState.Error(sendResult.getString("error"))
            return
        }
        _nxfrState.value = NxfrState.Transferring(0f, 0, filePath, isSending = true)

        // Pump for progress.
        pumpLoop(handle, isSending = true)
    }

    /** Pump events from a session handle, updating state. */
    private suspend fun pumpLoop(handle: Long, isSending: Boolean) {
        while (isActive) {
            val eventJson = withContext(Dispatchers.IO) {
                NxfrBridge.nxfr_pump(handle)
            }
            val event = JSONObject(eventJson)
            Log.d(TAG, "pumpLoop raw: $eventJson")
            when (event.optString("event")) {
                "offer" -> {
                    _nxfrState.value = NxfrState.Offering(
                        handle = handle,
                        displayName = event.optString("display_name"),
                        totalSize = event.optLong("total_size"),
                        totalFiles = event.optInt("total_files"),
                        peerName = event.optString("peer_name")
                    )

                    val prefs = getSharedPreferences("nxfr_prefs", android.content.Context.MODE_PRIVATE)
                    val globalAuto = prefs.getInt("auto_accept_global", 0)

                    // TOFU Lock: auto-accept ONLY applies to paired devices.
                    // Unpaired senders always require manual consent.
                    var shouldAccept = false
                    if (globalAuto >= 1) {
                        val peerDeviceId = event.optString("device_id")
                        val storeDir = identityDir()
                        val pairedListJson = withContext(Dispatchers.IO) {
                            NxfrBridge.nxfr_paired_list(storeDir)
                        }
                        val pairedList = JSONObject(pairedListJson)
                        if (!pairedList.has("error")) {
                            val devices = pairedList.optJSONArray("devices")
                            if (devices != null) {
                                for (i in 0 until devices.length()) {
                                    val dev = devices.getJSONObject(i)
                                    if (dev.optString("device_id") == peerDeviceId) {
                                        // Peer IS paired.
                                        if (globalAuto == 2) {
                                            // "Everyone" (really: all paired devices)
                                            shouldAccept = true
                                        } else if (globalAuto == 1 && dev.optString("auto_accept") == "always") {
                                            // "Paired" with per-device auto-accept
                                            shouldAccept = true
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        if (!shouldAccept && peerDeviceId.isNotEmpty()) {
                            Log.i(TAG, "TOFU: peer $peerDeviceId NOT paired — forcing consent dialog")
                        }
                    }

                    if (shouldAccept) {
                        val confirmJson = withContext(Dispatchers.IO) {
                            NxfrBridge.nxfr_confirm(handle, true)
                        }
                        Log.i(TAG, "Auto-accepted transfer: $confirmJson")
                    } else {
                        Log.i(TAG, "Transfer offer pending manual approval.")
                    }
                }
                "progress" -> {
                    val sent = event.optLong("bytes_sent")
                    val total = event.optLong("total_bytes")
                    val progress = if (total > 0) sent.toFloat() / total else 0f
                    _nxfrState.value = NxfrState.Transferring(
                        progress = progress,
                        total = total,
                        fileName = event.optString("file_name"),
                        isSending = isSending
                    )
                }
                "complete" -> {
                    _nxfrState.value = NxfrState.Complete(event.optString("file_path").ifEmpty { null })
                    Log.i(TAG, "Transfer complete: ${event.optString("file_path")}")
                    NxfrBridge.nxfr_close(handle)
                    activeSessionHandle = 0
                    return
                }
                "error" -> {
                    _nxfrState.value = NxfrState.Error(event.optString("message"))
                    Log.e(TAG, "Transfer error: ${event.optString("message")}")
                    NxfrBridge.nxfr_close(handle)
                    activeSessionHandle = 0
                    return
                }
                "disconnected" -> {
                    _nxfrState.value = NxfrState.Error("Session disconnected")
                    activeSessionHandle = 0
                    return
                }
                "none" -> {
                    delay(50) // Poll interval.
                }
            }
        }
    }

    /** Return the identity directory path (persistent filesDir, never cacheDir). */
    private fun identityDir(): String {
        return filesDir.resolve("nxfr-identity").absolutePath
    }

    private val isActive: Boolean
        get() = serviceScope.isActive

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, com.nxfr.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NxfrApp.CHANNEL_TRANSFER)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }


}
