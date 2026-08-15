package com.nxfr.android.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.nxfr.android.R
import com.nxfr.android.discovery.DeviceUiModel
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import com.nxfr.android.staging.ContactsVCardExporter
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository
import com.nxfr.android.ui.icons.NxfrIcons
import com.nxfr.android.ui.components.AttachChipRail
import com.nxfr.android.ui.components.DeviceDeckCard
import com.nxfr.android.ui.components.StagedFilmstrip
import com.nxfr.android.ui.dialogs.SendModeExplanationDialog
import com.nxfr.android.ui.dialogs.TextComposeDialog
import com.nxfr.android.ui.sheets.InstalledAppsSheet
import com.nxfr.android.ui.sheets.ManualConnectSheet
import com.nxfr.android.ui.sheets.StagingEditSheet
import com.nxfr.android.ui.sheets.TroubleshootSheet
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class SendMode(val label: String) {
    SINGLE("Single"),
    MULTIPLE("Multiple"),
    WEB_SHARE("Link")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    devices: List<DeviceUiModel> = emptyList(),
    isScanning: Boolean = false,
    isProbing: Boolean = false,
    showHotspotBanner: Boolean = false,
    onRefresh: () -> Unit = {},
    onDeviceTap: (DeviceUiModel) -> Unit = {},
    onNavigateToWebShare: () -> Unit = {},
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var showManualConnectSheet by remember { mutableStateOf(false) }
    var showStagingEditSheet by remember { mutableStateOf(false) }
    var showInstalledAppsSheet by remember { mutableStateOf(false) }
    var showTextComposeDialog by remember { mutableStateOf(false) }
    var showModeExplanationDialog by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var sendMode by remember { mutableStateOf(SendMode.SINGLE) }
    var queuedDeviceIds by remember { mutableStateOf(setOf<String>()) }
    var isConnecting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe StagingRepository
    val stagedItems by StagingRepository.stagedItems.collectAsState()

    // Observe NxfrState for connect result.
    val nxfrState by NxfrService.nxfrState.collectAsState()

    LaunchedEffect(nxfrState) {
        when (val state = nxfrState) {
            is NxfrState.ManualConnected -> {
                isConnecting = false
            }
            is NxfrState.Complete -> {
                if (sendMode == SendMode.SINGLE) {
                    StagingRepository.clear()
                    StagingRepository.cleanStagingCache(context)
                }
            }
            is NxfrState.Error -> {
                isConnecting = false
                val displayMsg = if (state.msg.contains("connect", ignoreCase = true) || state.msg.contains("timeout", ignoreCase = true) || state.msg.contains("refused", ignoreCase = true)) {
                    "NODE UNREACHABLE — check IP, port, firewall"
                } else {
                    state.msg
                }
                Toast.makeText(context, displayMsg, Toast.LENGTH_LONG).show()
                snackbarHostState.showSnackbar(displayMsg)
            }
            else -> {}
        }
    }

    // ── Pickers ──
    // 1. Files
    val filesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val newItems = uris.map { uri ->
                    val (name, size) = queryUriDetails(context, uri)
                    StagedItem(
                        id = UUID.randomUUID().toString(),
                        type = StagedType.FILE,
                        displayName = name,
                        sizeBytes = size,
                        uri = uri
                    )
                }
                withContext(Dispatchers.Main) {
                    StagingRepository.addItems(context, newItems)
                }
            }
        }
    }

    // 2. Media
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val newItems = uris.map { uri ->
                    val (name, size) = queryUriDetails(context, uri)
                    StagedItem(
                        id = UUID.randomUUID().toString(),
                        type = StagedType.MEDIA,
                        displayName = name,
                        sizeBytes = size,
                        uri = uri
                    )
                }
                withContext(Dispatchers.Main) {
                    StagingRepository.addItems(context, newItems)
                }
            }
        }
    }

    // 3. Folder
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val folderName = docFile?.name ?: "Folder"
                val (fileCount, totalBytes) = calculateFolderStats(context, docFile)
                val stagedItem = StagedItem(
                    id = UUID.randomUUID().toString(),
                    type = StagedType.FOLDER,
                    displayName = folderName,
                    sizeBytes = totalBytes,
                    uri = treeUri,
                    isFolder = true,
                    fileCount = fileCount
                )
                withContext(Dispatchers.Main) {
                    StagingRepository.addItems(context, listOf(stagedItem))
                }
            }
        }
    }

    // 4. Contacts (.vcf)
    var pendingContactUri by remember { mutableStateOf<Uri?>(null) }
    var showContactPermissionRationale by remember { mutableStateOf(false) }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = pendingContactUri
            if (uri != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    val res = ContactsVCardExporter.exportContact(context, uri)
                    handleContactExportResult(res, context)
                }
            }
        } else {
            Toast.makeText(
                context,
                "Contacts permission denied — cannot export vCard",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingContactUri = null
    }

    val contactsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri = result.data?.data
            if (contactUri != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    val res = ContactsVCardExporter.exportContact(context, contactUri)
                    if (res.needsPermission) {
                        pendingContactUri = contactUri
                        withContext(Dispatchers.Main) {
                            showContactPermissionRationale = true
                        }
                    } else {
                        handleContactExportResult(res, context)
                    }
                }
            }
        }
    }

    // Camera QR Scanner
    var showCameraPermissionRationale by remember { mutableStateOf(false) }

    val qrScanLauncher = rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        if (result.contents != null) {
            when (val scanRes = com.nxfr.android.transfer.NxfrQrTicketParser.parse(result.contents)) {
                is com.nxfr.android.transfer.QrScanResult.ConnectTicket -> {
                    val addr = scanRes.addr
                    startSendFlow(context, coroutineScope, addr)
                }
                is com.nxfr.android.transfer.QrScanResult.WebUploadLink -> {
                    Toast.makeText(context, "That's a web-upload link — open it in a browser", Toast.LENGTH_LONG).show()
                }
                is com.nxfr.android.transfer.QrScanResult.Invalid -> {
                    Toast.makeText(context, "Not an NXFR code", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val options = com.journeyapps.barcodescanner.ScanOptions().apply {
                setPrompt("Scan an NXFR QR code")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        } else {
            Toast.makeText(context, "Camera permission denied — fallback to manual connect", Toast.LENGTH_LONG).show()
            showTroubleshootSheet = true
        }
    }

    if (showCameraPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionRationale = false },
            title = { Text("Camera Permission Required") },
            text = { Text("Camera permission is required to scan NXFR device QR codes for fast connection and pairing.") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraPermissionRationale = false
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }) {
                    Text("Grant")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraPermissionRationale = false
                    showTroubleshootSheet = true
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = deck.rootBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (sendMode == SendMode.MULTIPLE && queuedDeviceIds.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val targets = devices.filter { queuedDeviceIds.contains(it.deviceId) }
                        targets.forEach { target ->
                            val addr = "${target.host}:${target.port}"
                            startSendFlow(context, coroutineScope, addr)
                        }
                    },
                    containerColor = deck.signalBeam,
                    contentColor = deck.rootBackground,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "TRANSMIT TO ${queuedDeviceIds.size} NODES →",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Hotspot Banner
            if (showHotspotBanner) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surfaceVariant)
                        .border(1.dp, deck.signalWarning)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.send_hotspot_banner),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissBanner) {
                        Text("DISMISS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = deck.signalWarning)
                    }
                }
            }

            // 2. Attach Rail (Always Accessible)
            AttachChipRail(
                onOpenFilePicker = { filesPicker.launch(arrayOf("*/*")) },
                onOpenMediaPicker = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onOpenTextComposer = { showTextComposeDialog = true },
                onPasteClipboard = { pasteFromClipboard(context, coroutineScope) },
                onOpenFolderPicker = { folderPicker.launch(null) },
                onOpenAppPicker = { showInstalledAppsSheet = true },
                onOpenContactsPicker = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                    contactsPicker.launch(intent)
                }
            )

            // 3. Staged Filmstrip (when items exist)
            StagedFilmstrip(
                stagedItems = stagedItems,
                onRemoveItem = { item -> StagingRepository.removeItem(item.id) },
                onClearAll = {
                    StagingRepository.clear()
                    StagingRepository.cleanStagingCache(context)
                },
                onOpenEditSheet = { showStagingEditSheet = true }
            )

            // 4. Scanning / Probing Progress Strip
            if (isScanning || isProbing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = deck.signalBeam,
                    trackColor = deck.surfaceContainer
                )
            }

            // 5. Nearby Nodes Header & Mode Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NEARBY NODES (${devices.size})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textSecondary
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Mode Dropdown Selector
                    Box {
                        Box(
                            modifier = Modifier
                                .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                                .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                                .clickable(role = Role.Button) { showModeMenu = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MODE: ${sendMode.name} ▼",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = deck.signalBeam
                            )
                        }

                        DropdownMenu(
                            expanded = showModeMenu,
                            onDismissRequest = { showModeMenu = false },
                            modifier = Modifier.background(deck.surface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("${if (sendMode == SendMode.SINGLE) "✓ " else ""}Single Recipient", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary) },
                                onClick = {
                                    sendMode = SendMode.SINGLE
                                    queuedDeviceIds = emptySet()
                                    showModeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("${if (sendMode == SendMode.MULTIPLE) "✓ " else ""}Multiple Recipients", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary) },
                                onClick = {
                                    sendMode = SendMode.MULTIPLE
                                    showModeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("${if (sendMode == SendMode.WEB_SHARE) "✓ " else ""}Share via Link", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary) },
                                onClick = {
                                    sendMode = SendMode.WEB_SHARE
                                    showModeMenu = false
                                    if (stagedItems.isNotEmpty()) {
                                        onNavigateToWebShare()
                                    } else {
                                        Toast.makeText(context, "Stage files first before sharing via link", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            HorizontalDivider(color = deck.gridLine)
                            DropdownMenuItem(
                                text = { Text("Mode Explanations", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.signalBeam) },
                                onClick = {
                                    showModeExplanationDialog = true
                                    showModeMenu = false
                                }
                            )
                        }
                    }
                }

                // Action Icons (Manual Connect, QR Scanner, Refresh, Diagnostics)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { showManualConnectSheet = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.GpsFixed, contentDescription = "Manual Connect", tint = deck.signalBeam, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = {
                            val permissionState = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.CAMERA
                            )
                            if (permissionState == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                val options = com.journeyapps.barcodescanner.ScanOptions().apply {
                                    setPrompt("Scan an NXFR QR code")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                }
                                qrScanLauncher.launch(options)
                            } else {
                                showCameraPermissionRationale = true
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(NxfrIcons.QrScan, contentDescription = "Scan QR", tint = deck.textSecondary, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = deck.textSecondary, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = { showTroubleshootSheet = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(NxfrIcons.Diagnostics, contentDescription = "Diagnostics", tint = deck.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 6. Device List or Empty Deck State
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(deck.surfaceContainer, RoundedCornerShape(4.dp))
                            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = deck.signalBeam
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "SCANNING LAN NODES [STANDBY]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (stagedItems.isEmpty()) "Attach payload above or broadcast to receive.\nNo LAN peers? Try 📡 DESERT mode from Home tab." else "Ensure target receiver has Visibility Breaker engaged.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = deck.textDim
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showTroubleshootSheet = true },
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text(
                                text = "DIAGNOSTICS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = deck.textSecondary
                            )
                        }

                        OutlinedButton(
                            onClick = { showManualConnectSheet = true },
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Icon(Icons.Outlined.GpsFixed, contentDescription = null, tint = deck.signalBeam, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MANUAL CONNECT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = deck.signalBeam
                            )
                        }
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedDevices, key = { it.deviceId }) { device ->
                        val isQueued = queuedDeviceIds.contains(device.deviceId)
                        DeviceDeckCard(
                            device = device,
                            isQueued = isQueued,
                            isMultipleMode = sendMode == SendMode.MULTIPLE,
                            onClick = {
                                if (sendMode == SendMode.MULTIPLE) {
                                    queuedDeviceIds = if (isQueued) {
                                        queuedDeviceIds - device.deviceId
                                    } else {
                                        queuedDeviceIds + device.deviceId
                                    }
                                } else {
                                    val addr = "${device.host}:${device.port}"
                                    startSendFlow(context, coroutineScope, addr)
                                    onDeviceTap(device)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Sheets & Modals ──
    if (showStagingEditSheet) {
        StagingEditSheet(
            onDismiss = { showStagingEditSheet = false },
            onAddMore = {
                showStagingEditSheet = false
                filesPicker.launch(arrayOf("*/*"))
            }
        )
    }

    if (showInstalledAppsSheet) {
        InstalledAppsSheet(
            onDismiss = { showInstalledAppsSheet = false }
        )
    }

    if (showTextComposeDialog) {
        TextComposeDialog(
            onDismiss = { showTextComposeDialog = false },
            onStaged = {
                Toast.makeText(context, "Text snippet staged", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showModeExplanationDialog) {
        SendModeExplanationDialog(onDismiss = { showModeExplanationDialog = false })
    }

    if (showTroubleshootSheet) {
        TroubleshootSheet(
            onDismiss = { showTroubleshootSheet = false },
            onOpenManualConnect = { showManualConnectSheet = true }
        )
    }

    if (showManualConnectSheet) {
        ManualConnectSheet(
            onDismiss = { showManualConnectSheet = false },
            onConnect = { addr ->
                startSendFlow(context, coroutineScope, addr)
            }
        )
    }

    if (showContactPermissionRationale) {
        AlertDialog(
            onDismissRequest = {
                showContactPermissionRationale = false
                pendingContactUri = null
            },
            title = {
                Text(
                    text = "EXPORT CONTACT AS .VCF",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Only used to export the contact YOU pick as .vcf — never synced, never uploaded.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = deck.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showContactPermissionRationale = false
                        contactPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = deck.signalBeam, contentColor = deck.rootBackground)
                ) {
                    Text("GRANT & EXPORT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showContactPermissionRationale = false
                        pendingContactUri = null
                    }
                ) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// ── Helpers ──
private fun startSendFlow(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    addr: String
) {
    coroutineScope.launch(Dispatchers.IO) {
        val stagingFolder = StagingRepository.prepareStagingDirectory(context)
        withContext(Dispatchers.Main) {
            val sendIntent = Intent(context, NxfrService::class.java).apply {
                action = NxfrService.ACTION_SEND
                putExtra(NxfrService.EXTRA_ADDR, addr)
                putExtra(NxfrService.EXTRA_FILE_PATH, stagingFolder.absolutePath)
            }
            context.startService(sendIntent)
            Toast.makeText(context, "Initiating transfer to $addr...", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun pasteFromClipboard(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboard.primaryClip
    if (clipData != null && clipData.itemCount > 0) {
        val item = clipData.getItemAt(0)
        val text = item.text?.toString() ?: item.uri?.toString() ?: ""
        if (text.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                val stagingDir = File(context.cacheDir, "nxfr_paste").apply { mkdirs() }
                val pasteFile = File(stagingDir, "clipboard_${System.currentTimeMillis()}.txt")
                pasteFile.writeText(text)

                val stagedItem = StagedItem(
                    id = UUID.randomUUID().toString(),
                    type = StagedType.TEXT,
                    displayName = pasteFile.name,
                    sizeBytes = pasteFile.length(),
                    localFile = pasteFile
                )
                withContext(Dispatchers.Main) {
                    StagingRepository.addItems(context, listOf(stagedItem))
                    Toast.makeText(context, "Pasted snippet staged", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    }
}

private fun countFilesInDirectory(docFile: DocumentFile): Pair<Int, Long> {
    var count = 0
    var bytes = 0L

    fun walk(df: DocumentFile) {
        if (df.isDirectory) {
            df.listFiles().forEach { child ->
                walk(child)
            }
        } else {
            count++
            bytes += df.length()
        }
    }
    walk(docFile)
    return count to bytes
}

private fun queryUriDetails(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file_${System.currentTimeMillis()}"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (_: Exception) {}
    return name to size
}

private fun calculateFolderStats(context: Context, docFile: DocumentFile?): Pair<Int, Long> {
    if (docFile == null || !docFile.exists()) return 0 to 0L
    var count = 0
    var bytes = 0L
    fun walk(df: DocumentFile) {
        if (df.isDirectory) {
            df.listFiles().forEach { walk(it) }
        } else {
            count++
            bytes += df.length()
        }
    }
    walk(docFile)
    return count to bytes
}

private suspend fun handleContactExportResult(
    res: ContactsVCardExporter.ExportResult,
    context: Context
) {
    if (res.file != null && res.file.exists()) {
        val stagedItem = StagedItem(
            id = UUID.randomUUID().toString(),
            type = StagedType.CONTACT,
            displayName = res.displayName ?: res.file.name,
            sizeBytes = res.file.length(),
            localFile = res.file
        )
        withContext(Dispatchers.Main) {
            StagingRepository.addItems(context, listOf(stagedItem))
            Toast.makeText(context, "Staged ${res.displayName ?: res.file.name}", Toast.LENGTH_SHORT).show()
        }
    } else {
        withContext(Dispatchers.Main) {
            val msg = res.error ?: "Failed to export contact as vCard"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}
