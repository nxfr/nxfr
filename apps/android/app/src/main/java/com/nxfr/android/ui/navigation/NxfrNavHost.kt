package com.nxfr.android.ui.navigation


import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nxfr.android.discovery.DeviceUiModel
import com.nxfr.android.discovery.HotspotAwareDiscovery
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.screens.ReceiveScreen
import com.nxfr.android.ui.screens.SendScreen
import com.nxfr.android.ui.screens.SettingsScreen
import com.nxfr.android.ui.screens.TransferScreen
import com.nxfr.android.ui.screens.WebUploadScreen
import com.nxfr.android.service.NxfrState
import com.nxfr.android.ui.dialogs.ConsentDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
fun NxfrNavHost(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                NxfrScreen.bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = stringResource(screen.labelResId)
                            )
                        },
                        label = { Text(stringResource(screen.labelResId)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        val isAnimationsEnabled = com.nxfr.android.ui.theme.LocalAnimationsEnabled.current
        NavHost(
            navController = navController,
            startDestination = NxfrScreen.Receive.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (isAnimationsEnabled) slideInHorizontally(initialOffsetX = { 120 }) + fadeIn(animationSpec = tween(300, easing = EaseInOut)) else androidx.compose.animation.EnterTransition.None
            },
            exitTransition = {
                if (isAnimationsEnabled) slideOutHorizontally(targetOffsetX = { -120 }) + fadeOut(animationSpec = tween(300, easing = EaseInOut)) else androidx.compose.animation.ExitTransition.None
            },
            popEnterTransition = {
                if (isAnimationsEnabled) slideInHorizontally(initialOffsetX = { -120 }) + fadeIn(animationSpec = tween(300, easing = EaseInOut)) else androidx.compose.animation.EnterTransition.None
            },
            popExitTransition = {
                if (isAnimationsEnabled) slideOutHorizontally(targetOffsetX = { 120 }) + fadeOut(animationSpec = tween(300, easing = EaseInOut)) else androidx.compose.animation.ExitTransition.None
            },
        ) {
            composable(NxfrScreen.Receive.route) {
                ReceiveScreen(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    onDeviceNameChanged = onDeviceNameChanged,
                    onReceiveViaLink = { navController.navigate(NxfrScreen.WebUpload.route) },
                    onScanQr = { navController.navigate(NxfrScreen.Send.route) }
                )
            }
            composable(NxfrScreen.Send.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                // Use service-owned discovery (beacon runs from service, not UI).
                val discovery = NxfrService.discovery
                    ?: remember { HotspotAwareDiscovery(context) }
                val discoveredDevices by discovery.devices.collectAsState()
                val isScanning by discovery.isScanning.collectAsState()
                val isProbing by discovery.isProbing.collectAsState()
                val showHotspot by discovery.showHotspotBanner.collectAsState()

                // If service discovery not yet started, start from Send tab as fallback.
                DisposableEffect(Unit) {
                    if (NxfrService.discovery == null) {
                        val storeDir = context.filesDir.absolutePath
                        discovery.startDiscovery(storeDir, deviceId, deviceName)
                    }
                    onDispose {
                        if (NxfrService.discovery == null) {
                            discovery.stopDiscovery()
                        }
                    }
                }

                // SAF file picker launcher.
                var pendingDevice by remember { mutableStateOf<DeviceUiModel?>(null) }
                val filePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val dev = pendingDevice ?: return@rememberLauncherForActivityResult
                    if (uri == null) return@rememberLauncherForActivityResult
                    // Copy URI to cache dir for FFI access.
                    val cacheFile = java.io.File(context.cacheDir, "send_${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Start send via service.
                    val sendIntent = Intent(context, NxfrService::class.java).apply {
                        action = NxfrService.ACTION_SEND
                        putExtra(NxfrService.EXTRA_ADDR, "${dev.host}:${dev.port}")
                        putExtra(NxfrService.EXTRA_FILE_PATH, cacheFile.absolutePath)
                    }
                    context.startService(sendIntent)
                    Log.i("NxfrNavHost", "Send started: ${cacheFile.name} → ${dev.name} (${dev.host}:${dev.port})")
                    navController.navigate(NxfrScreen.Transfer.route)
                }

                SendScreen(
                    devices = discoveredDevices,
                    isScanning = isScanning,
                    isProbing = isProbing,
                    showHotspotBanner = showHotspot,
                    onRefresh = { discovery.refreshProbe() },
                    onDismissBanner = { discovery.dismissBanner() },
                    onDeviceTap = { device ->
                        Log.i("NxfrNavHost", "Device tapped: ${device.name} @ ${device.host}:${device.port}")
                        pendingDevice = device
                        filePicker.launch(arrayOf("*/*"))
                    },
                    onNavigateToWebShare = {
                        navController.navigate(NxfrScreen.WebShare.route)
                    }
                )
            }
            composable(NxfrScreen.Settings.route) {
                SettingsScreen(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    onDeviceNameChanged = onDeviceNameChanged,
                )
            }
            composable(NxfrScreen.Transfer.route) {
                TransferScreen(
                    onCancel = {
                        Log.i("NxfrNavHost", "TransferScreen: Cancel button tapped")
                        NxfrService.cancelActiveTransfer()
                        navController.popBackStack()
                    },
                    onComplete = {
                        navController.popBackStack()
                    },
                    onSendAnother = {
                        navController.popBackStack(NxfrScreen.Send.route, inclusive = false)
                    }
                )
            }
            composable(NxfrScreen.WebUpload.route) {
                WebUploadScreen(
                    onStop = { navController.popBackStack() }
                )
            }
            composable(NxfrScreen.WebShare.route) {
                val stagedItems by com.nxfr.android.staging.StagingRepository.stagedItems.collectAsState()
                com.nxfr.android.ui.screens.WebShareScreen(
                    stagedItems = stagedItems,
                    onStop = { navController.popBackStack() }
                )
            }
        }
        
        val nxfrState by NxfrService.nxfrState.collectAsState()
        val scope = rememberCoroutineScope()
        
        if (nxfrState is NxfrState.Offering) {
            val offering = nxfrState as NxfrState.Offering
            ConsentDialog(
                senderName = offering.peerName,
                fileCount = offering.totalFiles,
                totalSizeFormatted = formatBytes(offering.totalSize),
                onAccept = {
                    scope.launch {
                        val confirmJson = withContext(Dispatchers.IO) {
                            NxfrService.NxfrBridge.nxfr_confirm(offering.handle, true)
                        }
                        android.util.Log.i("NxfrNavHost", "Consent accepted: $confirmJson")
                        navController.navigate(NxfrScreen.Transfer.route)
                    }
                },
                onReject = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            NxfrService.NxfrBridge.nxfr_confirm(offering.handle, false)
                        }
                        android.util.Log.i("NxfrNavHost", "Consent rejected")
                    }
                }
            )
        }
    }
}
