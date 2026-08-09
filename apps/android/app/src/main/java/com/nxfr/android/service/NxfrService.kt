package com.nxfr.android.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nxfr.android.NxfrApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class NxfrState {
    data object Idle : NxfrState()
    data object Listening : NxfrState()
    data class Transferring(val progress: Float, val total: Long) : NxfrState()
    data class Error(val msg: String) : NxfrState()
}

class NxfrService : Service() {

    companion object {
        init {
            System.loadLibrary("nxfr_ffi")
        }
    }

    /**
     * JNI bridge to libnxfr_ffi.so.
     * All protocol logic (CBOR, framing, TLS) stays in Rust.
     * Kotlin only passes strings/handles and receives JSON events.
     */
    object NxfrBridge {
        // ── Identity ───────────────────────────────────────
        external fun nxfr_identity_generate(storeDir: String): String
        external fun nxfr_identity_load(storeDir: String): String

        // ── Connection ─────────────────────────────────────
        external fun nxfr_connect(addr: String, identityJson: String): String
        external fun nxfr_listen(port: Int, identityJson: String): String
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()
        _nxfrState.value = NxfrState.Listening
        return START_STICKY
    }

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
