package com.nxfr.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.R
import com.nxfr.android.discovery.DeviceUiModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendScreen(
    devices: List<DeviceUiModel> = emptyList(),
    isScanning: Boolean = false,
    isProbing: Boolean = false,
    showHotspotBanner: Boolean = false,
    onRefresh: () -> Unit = {},
    onDeviceTap: (DeviceUiModel) -> Unit = {},
    onManualConnect: (String) -> Unit = {},
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showTroubleshootSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTroubleshootSheet = true }
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_device))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. File type chip row
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { },
                    label = { Text(stringResource(R.string.send_file)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null) }
                )
                FilterChip(
                    selected = false,
                    onClick = { },
                    label = { Text(stringResource(R.string.send_media)) },
                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) }
                )
                FilterChip(
                    selected = false,
                    onClick = { },
                    label = { Text(stringResource(R.string.send_text)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.TextSnippet, contentDescription = null) }
                )
                FilterChip(
                    selected = false,
                    onClick = { },
                    label = { Text(stringResource(R.string.send_folder)) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) }
                )
            }

            // 2. Hotspot banner
            if (showHotspotBanner) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.send_hotspot_banner),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissBanner) {
                            Text(stringResource(android.R.string.cancel)) // Assuming a general dismiss or android cancel text
                        }
                    }
                }
            }

            // 3. Scanning status
            if (isScanning || isProbing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = if (isScanning) stringResource(R.string.send_scanning) else stringResource(R.string.send_probing),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. "Nearby devices" header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.send_nearby_devices), // Assumed string id
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cd_refresh_devices))
                    }
                    IconButton(onClick = { showTroubleshootSheet = true }) {
                        Icon(Icons.Outlined.Lan, contentDescription = stringResource(R.string.cd_manual_connect))
                    }
                }
            }

            // 5. Device cards or 6. Empty state
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WifiFind,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.send_no_devices),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.send_no_devices_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { showTroubleshootSheet = true }) {
                        Text(stringResource(R.string.send_troubleshoot))
                    }
                }
            } else {
                val sortedDevices = remember(devices) {
                    devices.sortedWith(compareByDescending<DeviceUiModel> { it.isPaired }.thenBy { it.name })
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedDevices, key = { it.deviceId }) { device ->
                        ElevatedCard(
                            onClick = { onDeviceTap(device) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val icon = when (device.platform.lowercase()) {
                                    "android" -> Icons.Outlined.PhoneAndroid
                                    "linux" -> Icons.Outlined.Computer
                                    "windows" -> Icons.Outlined.DesktopWindows
                                    else -> Icons.Outlined.Devices
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (device.isPaired) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Star,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = stringResource(R.string.send_paired_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (device.isDirect) {
                                            Text(
                                                text = stringResource(R.string.send_direct_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTroubleshootSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTroubleshootSheet = false }
        ) {
            TroubleshootSheetContent(
                onManualConnect = {
                    onManualConnect(it)
                    showTroubleshootSheet = false
                }
            )
        }
    }
}

@Composable
fun TroubleshootSheetContent(onManualConnect: (String) -> Unit) {
    var manualIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.send_troubleshoot_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        val steps = listOf(
            R.string.send_troubleshoot_step_1,
            R.string.send_troubleshoot_step_2,
            R.string.send_troubleshoot_step_3,
            R.string.send_troubleshoot_step_4
        )
        
        steps.forEach { stepRes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(stepRes)) // Assumed string ids for steps
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.send_manual_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.send_manual_hint)) },
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { onManualConnect(manualIp) },
            modifier = Modifier.fillMaxWidth(),
            enabled = manualIp.isNotBlank()
        ) {
            Text(stringResource(R.string.send_connect))
        }
    }
}
