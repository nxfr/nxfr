package com.nxfr.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class NxfrState {
    object Idle : NxfrState()
    object Listening : NxfrState()
    data class Transferring(val progress: Float, val total: Long) : NxfrState()
    data class Error(val msg: String) : NxfrState()
}

class NxfrService : Service() {
    companion object {
        init {
            System.loadLibrary("nxfr_ffi")
        }
    }

    object NxfrBridge {
        // external fun startServer()
        // external fun sendFile()
    }
    
    private val _nxfrState = MutableStateFlow<NxfrState>(NxfrState.Idle)
    val nxfrState: StateFlow<NxfrState> = _nxfrState.asStateFlow()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground service and update state
        _nxfrState.value = NxfrState.Listening
        return START_STICKY
    }
}
