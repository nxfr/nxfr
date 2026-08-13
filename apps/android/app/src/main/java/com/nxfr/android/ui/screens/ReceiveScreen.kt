package com.nxfr.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.R
import com.nxfr.android.service.NxfrService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    deviceName: String = "My Device",
    deviceId: String = "",
    onDeviceNameChanged: (String) -> Unit = {},
    onReceiveViaLink: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isVisible by NxfrService.isListening.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("nxfr_prefs", android.content.Context.MODE_PRIVATE) }
    var autoAcceptState by remember { mutableIntStateOf(sharedPrefs.getInt("auto_accept_global", 0)) } // 0: Off, 1: Paired, 2: Everyone
    var showRenameDialog by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf(deviceName) }
    var showWarningDialog by remember { mutableStateOf(false) }
    var saveFolderPath by remember { mutableStateOf<Uri?>(null) }
    var showInfoSheet by remember { mutableStateOf(false) }
    
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> saveFolderPath = uri }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Top-right icons
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { /* TODO: history */ }) {
                Icon(Icons.Default.History, contentDescription = "History")
            }
            IconButton(onClick = { showInfoSheet = true }) {
                Icon(Icons.Default.Info, contentDescription = "Info")
            }
        }
        
        // 1. Dual-Ring Radar Pulse Hero (200dp) with status chip
        val isListening by NxfrService.isListening.collectAsState()
        val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
        
        val maxScale = if (isListening) 1.5f else 1.1f
        val maxAlpha = if (isListening) 0.6f else 0.15f

        val pulseProgress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOut),
                repeatMode = RepeatMode.Restart
            ),
            label = "PulseProgress"
        )

        Box(
            contentAlignment = Alignment.Center, 
            modifier = Modifier
                .size(220.dp)
                .padding(top = 16.dp, bottom = 8.dp)
        ) {
            // Ring 1 (Inner expanding pulse)
            val ring1Scale = 1.0f + (pulseProgress * (maxScale - 1.0f))
            val ring1Alpha = maxAlpha * (1.0f - pulseProgress)
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = ring1Scale
                        scaleY = ring1Scale
                        this.alpha = ring1Alpha
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            ) {}

            // Ring 2 (Outer delayed pulse)
            val ring2Progress = (pulseProgress + 0.5f) % 1.0f
            val ring2Scale = 1.0f + (ring2Progress * (maxScale - 1.0f))
            val ring2Alpha = maxAlpha * (1.0f - ring2Progress)
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = ring2Scale
                        scaleY = ring2Scale
                        this.alpha = ring2Alpha
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            ) {}
            
            // Central Glowing Node
            Surface(
                modifier = Modifier.size(90.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp,
                shadowElevation = if (isListening) 8.dp else 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "N",
                        fontSize = 54.sp,
                        color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Status Chip Overlay at bottom of hero
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 0.dp),
                shape = MaterialTheme.shapes.small,
                color = if (isListening) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isListening) "🏠 Visible on LAN" else "🙈 Hidden",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isListening) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. Device alias + short ID
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { 
                newDeviceName = deviceName
                showRenameDialog = true 
            }
        ) {
            val shortId = if (deviceId.length >= 4) deviceId.substring(0, 4) else deviceId
            Text(
                text = "$deviceName #$shortId",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.receive_rename_device),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text(stringResource(R.string.receive_rename_device)) },
                text = {
                    OutlinedTextField(
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text(stringResource(R.string.receive_device_name_label)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { 
                        onDeviceNameChanged(newDeviceName)
                        showRenameDialog = false 
                    }) {
                        Text(stringResource(R.string.receive_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text(stringResource(R.string.receive_cancel))
                    }
                }
            )
        }

        // 3. Visibility toggle
        ElevatedCard(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.receive_visibility_toggle),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.receive_visibility_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isVisible,
                    onCheckedChange = { enabled ->
                        try {
                            (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        } catch (_: Exception) {}
                        val prefs = context.getSharedPreferences("nxfr_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("visible_enabled", enabled).apply()
                        val intent = Intent(context, NxfrService::class.java)
                        if (enabled) {
                            context.startService(intent)
                        } else {
                            // Stop listening but keep service alive
                            NxfrService.stopListening(context)
                        }
                    },
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        if (isVisible) {
            OutlinedButton(
                onClick = onReceiveViaLink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.receive_via_link))
            }
        }

        // 4. Auto-accept segmented buttons
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.receive_auto_accept_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    stringResource(R.string.receive_auto_accept_off),
                    stringResource(R.string.receive_auto_accept_paired),
                    stringResource(R.string.receive_auto_accept_everyone)
                )
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = autoAcceptState == index,
                        onClick = { 
                            if (index == 2 && autoAcceptState != 2) {
                                showWarningDialog = true
                            } else {
                                autoAcceptState = index 
                                sharedPrefs.edit().putInt("auto_accept_global", index).apply()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        enabled = true
                    ) {
                        Text(label)
                    }
                }
            }
        }

        if (showWarningDialog) {
            AlertDialog(
                onDismissRequest = { showWarningDialog = false },
                title = { Text(stringResource(R.string.receive_warning)) },
                text = { Text(stringResource(R.string.receive_auto_accept_everyone_warning)) },
                confirmButton = {
                    TextButton(onClick = { 
                        autoAcceptState = 2
                        sharedPrefs.edit().putInt("auto_accept_global", 2).apply()
                        showWarningDialog = false 
                    }) {
                        Text(stringResource(R.string.receive_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWarningDialog = false }) {
                        Text(stringResource(R.string.receive_cancel))
                    }
                }
            )
        }

        // 5. Save-to folder row
        ElevatedCard(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { folderPicker.launch(null) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = stringResource(R.string.receive_folder_icon),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.receive_save_to),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = saveFolderPath?.toString() ?: stringResource(R.string.receive_default_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.receive_change_folder))
                }
            }
        }

        // 6. Active transfers section
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.receive_active_transfers),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ElevatedCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.receive_no_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Add bottom padding for better scroll feel
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showInfoSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.receive_info_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Device Name: $deviceName",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                HorizontalDivider()
                
                Text("IP Addresses", style = MaterialTheme.typography.titleMedium)
                val ips = getDeviceIps(context)
                if (ips.isEmpty()) {
                    Text("Not connected", style = MaterialTheme.typography.bodyMedium)
                } else {
                    ips.forEach { ip ->
                        Text(ip, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider()
                
                Text("Ports", style = MaterialTheme.typography.titleMedium)
                Text("17394 (NXFR Protocol)", style = MaterialTheme.typography.bodyMedium)
                Text("17396 (Web Upload)", style = MaterialTheme.typography.bodyMedium)
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun getDeviceIps(context: android.content.Context): List<String> {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        val ips = mutableListOf<Pair<String, String>>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    ips.add(iface.name to (addr.hostAddress ?: ""))
                }
            }
        }
        // Prioritize wlan0 and ap0
        return ips.sortedByDescending { 
            it.first.startsWith("wlan") || it.first.startsWith("ap") 
        }.map { "${it.second} (${it.first})" }
    } catch (_: Exception) {
        return emptyList()
    }
}
