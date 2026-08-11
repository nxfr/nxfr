package com.nxfr.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.R
import com.nxfr.android.discovery.DeviceUiModel
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import com.nxfr.android.ui.parseAddr
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendScreen(
    devices: List<DeviceUiModel> = emptyList(),
    isScanning: Boolean = false,
    isProbing: Boolean = false,
    showHotspotBanner: Boolean = false,
    onRefresh: () -> Unit = {},
    onDeviceTap: (DeviceUiModel) -> Unit = {},
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Manual connection state: addr → peerName.
    var manualDevice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var manualHandle by remember { mutableStateOf(0L) }

    // Observe NxfrState for connect result.
    val nxfrState by NxfrService.nxfrState.collectAsState()

    // React to ManualConnected / Error while connecting.
    LaunchedEffect(nxfrState) {
        if (isConnecting) {
            when (val state = nxfrState) {
                is NxfrState.ManualConnected -> {
                    isConnecting = false
                    manualDevice = state.addr to state.peerName
                    manualHandle = state.handle
                }
                is NxfrState.Error -> {
                    isConnecting = false
                    snackbarHostState.showSnackbar(state.msg)
                }
                else -> {} // Still waiting.
            }
        }
    }

    // SAF file picker: copy to cache, then send.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val addr = manualDevice?.first ?: return@rememberLauncherForActivityResult
        val cached = copyUriToCache(context, uri)
        if (cached != null) {
            val sendIntent = Intent(context, NxfrService::class.java).apply {
                action = NxfrService.ACTION_SEND
                putExtra(NxfrService.EXTRA_ADDR, addr)
                putExtra(NxfrService.EXTRA_FILE_PATH, cached.absolutePath)
            }
            context.startService(sendIntent)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            Text(stringResource(android.R.string.cancel))
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
                    text = stringResource(R.string.send_nearby_devices),
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

            // 5. Manual device card (pinned at top if connected)
            if (manualDevice != null) {
                val (addr, peerName) = manualDevice!!
                ElevatedCard(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.send_manual_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = addr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 6. Device cards or empty state
            if (devices.isEmpty() && manualDevice == null) {
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
                isConnecting = isConnecting,
                onManualConnect = { addr ->
                    val parsed = parseAddr(addr)
                    if (parsed != null) {
                        isConnecting = true
                        val intent = Intent(context, NxfrService::class.java).apply {
                            action = NxfrService.ACTION_CONNECT
                            putExtra(NxfrService.EXTRA_ADDR, "${parsed.first}:${parsed.second}")
                        }
                        context.startService(intent)
                        showTroubleshootSheet = false
                    }
                    // Validation error is shown inline via TroubleshootSheetContent.
                }
            )
        }
    }
}

@Composable
fun TroubleshootSheetContent(
    isConnecting: Boolean = false,
    onManualConnect: (String) -> Unit
) {
    var manualIp by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

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
                Text(stringResource(stepRes))
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
            onValueChange = {
                manualIp = it
                showError = false
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.send_manual_hint)) },
            singleLine = true,
            isError = showError,
            supportingText = if (showError) {
                { Text(stringResource(R.string.send_connect_invalid_addr)) }
            } else null
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (parseAddr(manualIp) == null) {
                    showError = true
                } else {
                    onManualConnect(manualIp)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = manualIp.isNotBlank() && !isConnecting
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.send_connecting))
            } else {
                Text(stringResource(R.string.send_connect))
            }
        }
    }
}

/**
 * Copy a content URI to the app's cache directory so the FFI can read it
 * by path.
 */
private fun copyUriToCache(context: Context, uri: Uri): File? {
    val fileName = getFileName(context, uri) ?: "nxfr_send_${System.currentTimeMillis()}"
    val cacheFile = File(context.cacheDir, fileName)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile
    } catch (e: Exception) {
        null
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}
