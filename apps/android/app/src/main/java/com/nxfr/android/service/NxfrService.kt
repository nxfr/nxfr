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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class NxfrState {
    data object Idle : NxfrState()
    data object Listening : NxfrState()
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

        fun setDeviceName(name: String) { _deviceName.value = name }
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
        external fun nxfr_pair_confirm(handle: Long, accepted: Boolean): String

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

    private val _nxfrState = MutableStateFlow<NxfrState>(NxfrState.Idle)
    val nxfrState: StateFlow<NxfrState> = _nxfrState.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerHandle: Long = 0
    private var activeSessionHandle: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadOrGenerateIdentity()
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
            ACTION_SEND -> {
                val addr = intent.getStringExtra(EXTRA_ADDR) ?: return START_STICKY
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_STICKY
                serviceScope.launch { doSendFile(addr, filePath) }
            }
            else -> {
                serviceScope.launch { startListening() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (activeSessionHandle != 0L) {
            NxfrBridge.nxfr_close(activeSessionHandle)
            activeSessionHandle = 0
        }
    }

    /** Accept loop: listen → accept → pump for offers. */
    private suspend fun startListening() {
        val storeDir = identityDir()
        // Identity already loaded in onCreate.

        val listenJson = withContext(Dispatchers.IO) {
            NxfrBridge.nxfr_listen(17394, storeDir)
        }
        val listenResult = JSONObject(listenJson)
        if (listenResult.has("error")) {
            _nxfrState.value = NxfrState.Error(listenResult.getString("error"))
            return
        }
        listenerHandle = listenResult.getLong("listener")
        _nxfrState.value = NxfrState.Listening
        Log.i(TAG, "Listening on port ${listenResult.getInt("port")}")

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
            when (event.optString("event")) {
                "offer" -> {
                    _nxfrState.value = NxfrState.Offering(
                        handle = handle,
                        displayName = event.optString("display_name"),
                        totalSize = event.optLong("total_size"),
                        totalFiles = event.optInt("total_files"),
                        peerName = event.optString("peer_name")
                    )
                    // Auto-accept for now (Phase 8: consent notification).
                    val confirmJson = withContext(Dispatchers.IO) {
                        NxfrBridge.nxfr_confirm(handle, true)
                    }
                    Log.i(TAG, "Auto-accepted transfer: $confirmJson")
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
        val notification = buildNotification("NXFR is running")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NxfrApp.CHANNEL_TRANSFER)
            .setContentTitle("NXFR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }


}
