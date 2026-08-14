package com.nxfr.android.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
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

        var activePort: Int = DEFAULT_PORT
        var discoveryTimeoutMs: Int = 5000
        var multicastAddress: String = DEFAULT_MULTICAST

        fun updateActivePortAndRebind(context: Context, newPort: Int) {
            activePort = newPort
            val inst = instance
            if (inst != null && _isListening.value) {
                inst.serviceScope.launch {
                    if (inst.listenerHandle != 0L) {
                        try { withContext(Dispatchers.IO) { NxfrBridge.nxfr_close(inst.listenerHandle) } } catch (_: Throwable) {}
                        inst.listenerHandle = 0
                        inst.listening = false
                        _isListening.value = false
                    }
                    inst.startListening()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Listening on $newPort", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun updateMulticastAddressAndRebind(context: Context, newMulticast: String) {
            multicastAddress = newMulticast
            val inst = instance
            if (inst != null && _isListening.value) {
                inst.serviceScope.launch {
                    val storeDir = inst.identityDir()
                    val did = _deviceId.value
                    val dname = _deviceName.value
                    if (did.isNotEmpty()) {
                        _discovery?.startDiscovery(storeDir, did, dname)
                    }
                }
            }
        }

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

        fun getIdentityDir(context: android.content.Context): String {
            return context.filesDir.resolve("nxfr-identity").absolutePath
        }

        fun recordHistory(
            context: android.content.Context,
            direction: String,
            peerName: String,
            peerId: String,
            fileCount: Int,
            totalBytes: Long,
            status: String,
            filePaths: List<String>
        ) {
            try {
                val storeDir = getIdentityDir(context)
                val json = org.json.JSONObject().apply {
                    put("id", 0)
                    put("ts_ms", System.currentTimeMillis())
                    put("direction", direction)
                    put("peer_name", peerName)
                    put("peer_id", peerId)
                    put("file_count", fileCount)
                    put("total_bytes", totalBytes)
                    put("status", status)
                    put("file_paths", org.json.JSONArray(filePaths))
                }.toString()
                NxfrBridge.nxfr_history_add(json, storeDir)
                Log.i("NxfrService", "History recorded: $direction $peerName status=$status")
            } catch (e: Throwable) {
                Log.w("NxfrService", "Failed to record history: ${e.message}")
            }
        }

        /** Stop the TCP listener and mDNS discovery without killing the service. */
        fun stopListening(context: android.content.Context? = null) {
            instance?.let { svc ->
                Log.i("NxfrService", "Stopping listener and discovery...")
                _discovery?.stopDiscovery()
                if (svc.listenerHandle != 0L) {
                    try { NxfrBridge.nxfr_close(svc.listenerHandle) } catch (e: Throwable) {
                        Log.e("NxfrService", "Failed to close listener: ${e.message}")
                        context?.let { ctx ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(ctx, "Stop failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    svc.listenerHandle = 0
                }
                svc.listening = false
                _isListening.value = false
                Log.i("NxfrService", "Listener stopped.")
                svc.evaluateLifecycleContract()
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

        // ── Web Upload / Share ──────────────────────────────
        external fun nxfr_web_start(port: Int, storeDir: String, pin: String): String
        external fun nxfr_web_share_start(port: Int, storeDir: String, pin: String, manifestJson: String): String
        external fun nxfr_web_stop(): String
        external fun nxfr_web_fingerprint(storeDir: String): String

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

        // ── Storage ─────────────────────────────────────────
        external fun nxfr_set_receive_dir(path: String): String
        external fun nxfr_derive_sas(
            deviceIdAHex: String,
            deviceIdBHex: String,
            exporterBytes: ByteArray
        ): String

        // ── History ─────────────────────────────────────────
        external fun nxfr_history_add(jsonRecord: String, storeDir: String): String
        external fun nxfr_history_list(limit: Int, storeDir: String): String
        external fun nxfr_history_clear(storeDir: String): String

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

        // Set receive directory to app-scoped external storage.
        val inbox = java.io.File(getExternalFilesDir(null), "inbox")
        inbox.mkdirs()
        val rdResult = NxfrBridge.nxfr_set_receive_dir(inbox.absolutePath)
        Log.i(TAG, "nxfr_set_receive_dir: $rdResult")
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
                // Reconcile: start on boot/intent ONLY if persisted flag is true.
                val prefs = getSharedPreferences("nxfr_prefs", MODE_PRIVATE)
                val visibleOn = prefs.getBoolean("visible_enabled", false)
                if (visibleOn) {
                    serviceScope.launch { startListening() }
                } else if (activeSessionHandle == 0L) {
                    Log.i(TAG, "Service started, but visibility is OFF and no active transfer. Stopping service.")
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                    } catch (_: Throwable) {}
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: User swiped app away, evaluating lifecycle contract...")
        evaluateLifecycleContract()
    }

    fun evaluateLifecycleContract() {
        val prefs = getSharedPreferences("nxfr_prefs", MODE_PRIVATE)
        val isVisible = prefs.getBoolean("visible_enabled", false)
        val hasActiveTransfer = activeSessionHandle != 0L

        Log.i(TAG, "[evaluateLifecycleContract] visible=$isVisible listening=$listening activeSession=$hasActiveTransfer")

        if (!isVisible && !listening && !hasActiveTransfer) {
            Log.i(TAG, "[evaluateLifecycleContract] Visibility OFF, idle, zero active transfers. Stopping foreground & service.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "stopForeground error: ${e.message}")
            }
            stopSelf()
        } else {
            updateNotificationContent(isVisible, hasActiveTransfer)
        }
    }

    private fun updateNotificationContent(isVisible: Boolean, hasActiveTransfer: Boolean) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val text = when {
            hasActiveTransfer -> "Transfer in progress…"
            isVisible -> "NXFR visible on LAN — tap to manage"
            else -> "NXFR direct transfer"
        }
        val intent = Intent(this, com.nxfr.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, NxfrApp.CHANNEL_TRANSFER)
            .setContentTitle("NXFR Direct Transfer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(1, notification)
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
            NxfrBridge.nxfr_listen(activePort, storeDir)
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
                NxfrBridge.nxfr_listen(activePort, storeDir)
            }
            listenResult = JSONObject(listenJson)
        }

        if (listenResult.has("error")) {
            val msg = listenResult.getString("error")
            Log.e(TAG, "Listen failed: $msg")
            _nxfrState.value = NxfrState.Error(msg)
            _isListening.value = false
            listening = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this@NxfrService, "Listen failed: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
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
                    val inboxPath = event.optString("file_path").ifEmpty { null }
                    var publishedPath: String? = inboxPath

                    // Publish inbox file → Downloads/NXFR via MediaStore.
                    if (!isSending && inboxPath != null) {
                        val file = java.io.File(inboxPath)
                        val fileSize = if (file.exists()) file.length() else 0L
                        publishedPath = publishToDownloads(file)
                        val notificationManager = com.nxfr.android.transfer.TransferNotificationManager(this)
                        notificationManager.showTransferCompleteNotification(
                            transferId = (handle and 0x7FFFFFFF).toInt(),
                            fileName = file.name,
                            fileSize = fileSize,
                            publishedPath = publishedPath ?: ""
                        )
                    }

                    if (com.nxfr.android.prefs.NxfrPreferences.saveToHistory.value) {
                        recordHistory(
                            context = this,
                            direction = if (isSending) "send" else "recv",
                            peerName = event.optString("peer_name").ifEmpty { "Peer Device" },
                            peerId = event.optString("peer_id"),
                            fileCount = 1,
                            totalBytes = if (!isSending && inboxPath != null) java.io.File(inboxPath).length() else 0L,
                            status = "complete",
                            filePaths = listOfNotNull(publishedPath)
                        )
                    }

                    _nxfrState.value = NxfrState.Complete(publishedPath)
                    Log.i(TAG, "Transfer complete: $publishedPath")
                    NxfrBridge.nxfr_close(handle)
                    activeSessionHandle = 0
                    return
                }
                "error" -> {
                    if (handle != activeSessionHandle) {
                        Log.i(TAG, "Transfer cancelled locally, ignoring error.")
                        return
                    }
                    val raw = event.optString("message")
                    val status = if (raw.contains("reject", ignoreCase = true)) "rejected" else "failed"
                    recordHistory(
                        context = this,
                        direction = if (isSending) "send" else "recv",
                        peerName = event.optString("peer_name").ifEmpty { "Peer Device" },
                        peerId = event.optString("peer_id"),
                        fileCount = 1,
                        totalBytes = 0L,
                        status = status,
                        filePaths = emptyList()
                    )
                    // Map storage errors to human-readable messages.
                    val human = when {
                        raw.contains("EROFS") || raw.contains("os error 30") || raw.contains("StorageError") ->
                            "Storage permission missing — check app permissions"
                        raw.contains("ENOSPC") || raw.contains("os error 28") || raw.contains("DiskFull") ->
                            "Receiver storage full"
                        raw.contains("EACCES") || raw.contains("os error 13") ->
                            "Storage access denied — check permissions"
                        raw.contains("PathTraversalAttempt") ->
                            "Rejected: unsafe filename"
                        else -> raw
                    }
                    _nxfrState.value = NxfrState.Error(human)
                    Log.e(TAG, "Transfer error: $raw (displayed: $human)")
                    NxfrBridge.nxfr_close(handle)
                    activeSessionHandle = 0
                    return
                }
                "disconnected" -> {
                    if (handle != activeSessionHandle) return
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

    /**
     * Publish an inbox file to the user-visible Downloads/NXFR directory via MediaStore.
     * On success: deletes inbox copy, returns Downloads path.
     * On failure (T3c): KEEPS the inbox copy, returns inbox path — never silently loses a file.
     */
    private fun publishToDownloads(inboxFile: java.io.File): String {
        return com.nxfr.android.storage.FilePublisher.publishToDownloads(this, inboxFile)
    }

}
