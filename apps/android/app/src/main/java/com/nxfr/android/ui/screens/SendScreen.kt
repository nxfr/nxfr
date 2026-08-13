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
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.nxfr.android.R
import com.nxfr.android.discovery.DeviceUiModel
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository
import com.nxfr.android.ui.components.SelectionGridCard
import com.nxfr.android.ui.components.StagingSummaryCard
import com.nxfr.android.ui.dialogs.TextComposeDialog
import com.nxfr.android.ui.parseAddr
import com.nxfr.android.ui.sheets.InstalledAppsSheet
import com.nxfr.android.ui.sheets.StagingEditSheet
import com.nxfr.android.ui.sheets.TroubleshootSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    val coroutineScope = rememberCoroutineScope()
    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var showStagingEditSheet by remember { mutableStateOf(false) }
    var showInstalledAppsSheet by remember { mutableStateOf(false) }
    var showTextComposeDialog by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe StagingRepository
    val stagedItems by StagingRepository.stagedItems.collectAsState()

    // Observe NxfrState for connect result.
    val nxfrState by NxfrService.nxfrState.collectAsState()

    // React to ManualConnected / Error while connecting / Complete.
    LaunchedEffect(nxfrState) {
        when (val state = nxfrState) {
            is NxfrState.ManualConnected -> {
                isConnecting = false
            }
            is NxfrState.Complete -> {
                StagingRepository.clear()
                StagingRepository.cleanStagingCache(context)
            }
            is NxfrState.Error -> {
                isConnecting = false
                snackbarHostState.showSnackbar(state.msg)
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
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            val doc = DocumentFile.fromTreeUri(context, uri)
            val name = doc?.name ?: "Folder"
            val (fileCount, totalSize) = countDocumentTree(context, doc)
            val item = StagedItem(
                id = UUID.randomUUID().toString(),
                type = StagedType.FOLDER,
                displayName = name,
                sizeBytes = totalSize,
                uri = uri,
                isFolder = true,
                fileCount = fileCount
            )
            withContext(Dispatchers.Main) {
                StagingRepository.addItem(context, item)
            }
        }
    }

    // 4. Contacts
    val contactsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            coroutineScope.launch(Dispatchers.IO) {
                val vcardItem = exportContactToVcard(context, uri)
                if (vcardItem != null) {
                    withContext(Dispatchers.Main) {
                        StagingRepository.addItem(context, vcardItem)
                    }
                }
            }
        }
    }

    // Camera QR Scanner Permission
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
                    Toast.makeText(context, "Camera permission denied — fallback to manual connect", Toast.LENGTH_LONG).show()
                    showTroubleshootSheet = true
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTroubleshootSheet = true }
            ) {
                Icon(Icons.Outlined.Lan, contentDescription = "Manual connect & Troubleshoot")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Hotspot banner
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

            // 2. Selection Grid or Staging Summary Card
            if (stagedItems.isEmpty()) {
                SelectionGridCard(
                    onOpenFilePicker = { filesPicker.launch(arrayOf("*/*")) },
                    onOpenMediaPicker = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onOpenTextComposer = { showTextComposeDialog = true },
                    onPasteClipboard = { pasteFromClipboard(context) },
                    onOpenFolderPicker = { folderPicker.launch(null) },
                    onOpenAppPicker = { showInstalledAppsSheet = true },
                    onOpenContactsPicker = {
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                        contactsPicker.launch(intent)
                    }
                )
            } else {
                StagingSummaryCard(
                    onEditStaging = { showStagingEditSheet = true },
                    onAddMore = { showStagingEditSheet = true }
                )
            }

            // 3. Scanning status
            if (isScanning || isProbing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = if (isScanning) stringResource(R.string.send_scanning) else stringResource(R.string.send_probing),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. "Nearby devices" header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.send_nearby_devices),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    IconButton(onClick = {
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
                    }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan QR code")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cd_refresh_devices))
                    }
                    IconButton(onClick = { showTroubleshootSheet = true }) {
                        Icon(Icons.Outlined.Lan, contentDescription = stringResource(R.string.cd_manual_connect))
                    }
                }
            }

            // 5. Device cards or empty state
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val floatTransition = rememberInfiniteTransition(label = "EmptyFloat")
                    val floatOffset by floatTransition.animateFloat(
                        initialValue = -4f,
                        targetValue = 4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "FloatOffset"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(120.dp)
                            .offset(y = floatOffset.dp)
                            .padding(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {}
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PhoneAndroid,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Outlined.Wifi,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(horizontal = 2.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Icon(
                                imageVector = Icons.Outlined.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Searching local network…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (stagedItems.isEmpty()) "Pick something to send, or use Share from any app." else "Ensure target device is visible on local Wi-Fi",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { showTroubleshootSheet = true }) {
                        Text("Troubleshoot connection")
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
                            onClick = {
                                val addr = "${device.host}:${device.port}"
                                startSendFlow(context, coroutineScope, addr)
                                onDeviceTap(device)
                            },
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
                                                    text = "Paired ⭐",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
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

    // Sheets & Dialogs
    if (showTroubleshootSheet) {
        TroubleshootSheet(onDismiss = { showTroubleshootSheet = false })
    }

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
        InstalledAppsSheet(onDismiss = { showInstalledAppsSheet = false })
    }

    if (showTextComposeDialog) {
        TextComposeDialog(
            onDismiss = { showTextComposeDialog = false },
            onStaged = { Toast.makeText(context, "Text snippet staged", Toast.LENGTH_SHORT).show() }
        )
    }
}

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

private fun pasteFromClipboard(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val text = item.text?.toString()
            val uri = item.uri

            if (!text.isNullOrBlank()) {
                val textDir = File(context.cacheDir, "text")
                textDir.mkdirs()
                val textFile = File(textDir, "clipboard_${System.currentTimeMillis()}.txt")
                textFile.writeText(text)

                StagingRepository.addItem(
                    context,
                    StagedItem(
                        id = UUID.randomUUID().toString(),
                        type = StagedType.TEXT,
                        displayName = textFile.name,
                        sizeBytes = textFile.length(),
                        localFile = textFile,
                        mimeType = "text/plain"
                    )
                )
                Toast.makeText(context, "Pasted text snippet to staging", Toast.LENGTH_SHORT).show()
            } else if (uri != null) {
                val (name, size) = queryUriDetails(context, uri)
                StagingRepository.addItem(
                    context,
                    StagedItem(
                        id = UUID.randomUUID().toString(),
                        type = StagedType.MEDIA,
                        displayName = name,
                        sizeBytes = size,
                        uri = uri
                    )
                )
                Toast.makeText(context, "Pasted clipboard content to staging", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to read clipboard", Toast.LENGTH_SHORT).show()
    }
}

private fun queryUriDetails(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Throwable) {}
    return name to size
}

private fun countDocumentTree(context: Context, doc: DocumentFile?): Pair<Int, Long> {
    if (doc == null || !doc.isDirectory) return 0 to 0L
    var count = 0
    var size = 0L
    doc.listFiles().forEach { child ->
        if (child.isDirectory) {
            val (c, s) = countDocumentTree(context, child)
            count += c
            size += s
        } else if (child.isFile) {
            count++
            size += child.length()
        }
    }
    return count to size
}

private fun exportContactToVcard(context: Context, contactUri: Uri): StagedItem? {
    try {
        var name = "contact"
        context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (idx >= 0) {
                    name = cursor.getString(idx) ?: "contact"
                }
            }
        }
        val cleanName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val contactsDir = File(context.cacheDir, "contacts")
        contactsDir.mkdirs()
        val vcardFile = File(contactsDir, "$cleanName.vcf")

        // Read VCard lookup URI
        val lookupKey = contactUri.lastPathSegment ?: ""
        val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)

        context.contentResolver.openInputStream(vcardUri)?.use { input ->
            vcardFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (!vcardFile.exists() || vcardFile.length() == 0L) {
            vcardFile.writeText("BEGIN:VCARD\nVERSION:3.0\nFN:$name\nEND:VCARD\n")
        }

        return StagedItem(
            id = UUID.randomUUID().toString(),
            type = StagedType.CONTACT,
            displayName = "$cleanName.vcf",
            sizeBytes = vcardFile.length(),
            localFile = vcardFile,
            mimeType = "text/x-vcard"
        )
    } catch (_: Throwable) {
        return null
    }
}
