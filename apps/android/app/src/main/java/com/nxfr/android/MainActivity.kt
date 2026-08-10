package com.nxfr.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.navigation.NxfrNavHost
import com.nxfr.android.ui.theme.NxfrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Start the foreground service for listening.
        startService(Intent(this, NxfrService::class.java))

        setContent {
            NxfrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var deviceName by rememberSaveable { mutableStateOf("My Device") }
                    // TODO: Load real device_id from KeystoreManager on launch.
                    var deviceId by rememberSaveable { mutableStateOf("0000000000000000") }

                    NxfrNavHost(
                        deviceName = deviceName,
                        deviceId = deviceId,
                        onDeviceNameChanged = { deviceName = it },
                    )
                }
            }
        }
    }
}
