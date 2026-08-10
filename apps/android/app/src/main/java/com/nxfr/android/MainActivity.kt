package com.nxfr.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

        // Start the foreground service (loads/generates identity in onCreate).
        startService(Intent(this, NxfrService::class.java))

        setContent {
            NxfrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Observe identity from NxfrService companion StateFlows.
                    val deviceId by NxfrService.deviceId.collectAsState()
                    val deviceName by NxfrService.deviceName.collectAsState()

                    NxfrNavHost(
                        deviceName = deviceName,
                        deviceId = deviceId,
                        onDeviceNameChanged = { NxfrService.setDeviceName(it) },
                    )
                }
            }
        }
    }
}
