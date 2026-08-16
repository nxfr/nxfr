package com.nxfr.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.navigation.NxfrNavHost
import com.nxfr.android.ui.theme.NxfrTheme
import com.nxfr.android.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleDeepLink(intent)

        // Start the foreground service (loads/generates identity in onCreate).
        startService(Intent(this, NxfrService::class.java))

        ThemePreference.init(this)
        com.nxfr.android.ui.theme.AnimationPreference.init(this)
        com.nxfr.android.prefs.NxfrPreferences.init(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val launcher = registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) {
                        android.widget.Toast.makeText(
                            this,
                            "Notifications off — transfers continue in background",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                launcher.launch(permission)
            }
        }

        setContent {
            val themeMode by ThemePreference.themeMode.collectAsState()
            NxfrTheme(
                darkTheme = when(themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            ) {
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

    override fun onStart() {
        super.onStart()
        NxfrService.updateUiForeground(true)
    }

    override fun onStop() {
        super.onStop()
        NxfrService.updateUiForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val dataStr = intent?.dataString ?: return
        if (dataStr.startsWith("nxfr://connect", ignoreCase = true)) {
            when (val scanRes = com.nxfr.android.transfer.NxfrQrTicketParser.parse(dataStr)) {
                is com.nxfr.android.transfer.QrScanResult.ConnectTicket -> {
                    val connectIntent = Intent(this, NxfrService::class.java).apply {
                        action = NxfrService.ACTION_CONNECT
                        putExtra(NxfrService.EXTRA_ADDR, scanRes.addr)
                    }
                    startService(connectIntent)
                    android.widget.Toast.makeText(this, "Connecting to ${scanRes.deviceId.take(8)}...", android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }
}
