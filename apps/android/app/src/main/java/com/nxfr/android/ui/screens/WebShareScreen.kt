package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nxfr.android.discovery.NetworkInterfaceHelper
import com.nxfr.android.service.NxfrService
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stages files for web sharing, then hosts them via the Rust TLS server.
 *
 * Large files (content URIs from the picker) are copied to a cache directory
 * in a background coroutine using a 64 KB buffer so memory stays flat.
 * A progress bar is shown while staging is in progress.
 */
@Composable
fun WebShareScreen(
    stagedItems: List<StagedItem>,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var shareUrl by remember { mutableStateOf("") }
    var rawShareToken by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var secondsRemaining by remember { mutableStateOf(600) } // 10 minutes
    val items = stagedItems

    var isPinProtected by remember { mutableStateOf(false) }
    var pinCode by remember { mutableStateOf(generateRandomPin()) }
    var showEditPinDialog by remember { mutableStateOf(false) }
    var tempPinInput by remember { mutableStateOf("") }

    val totalSize = remember(stagedItems) { StagingRepository.calculateTotalSize() }
    val totalFiles = remember(stagedItems) { StagingRepository.calculateTotalFiles() }

    var nativeError by remember { mutableStateOf<String?>(null) }

    // ── Staging state (background copy of content URIs → cache) ────
    var stagingProgress by remember { mutableFloatStateOf(0f) }
    var stagingLabel by remember { mutableStateOf("") }
    var manifestJsonStr by remember { mutableStateOf<String?>(null) }

    fun startServerWithPin(pin: String?) {
        val manifest = manifestJsonStr ?: return
        val storeDir = NxfrService.getIdentityDir(context)
        try {
            NxfrService.NxfrBridge.nxfr_web_stop()
            val pinParam = pin ?: ""
            val jsonStr = NxfrService.NxfrBridge.nxfr_web_share_start(17396, storeDir, pinParam, manifest)
            val res = JSONObject(jsonStr)

            if (res.optString("status") == "started") {
                val port = res.optInt("port", 17396)
                val token = res.optString("token", "")
                rawShareToken = token
                val ip = NetworkInterfaceHelper.getPrimaryLocalIp(context)
                if (ip != null && ip.isNotEmpty()) {
                    val url = if (pinParam.isNotEmpty()) {
                        "https://$ip:$port/"
                    } else {
                        "https://$ip:$port/#t=$token"
                    }
                    shareUrl = url
                    qrBitmap = generateQrBitmap(url)
                } else {
                    shareUrl = ""
                    qrBitmap = null
                }

                try {
                    val fpJson = NxfrService.NxfrBridge.nxfr_web_fingerprint(storeDir)
                    val fpObj = JSONObject(fpJson)
                    fingerprint = fpObj.optString("spki_sha256", "Unknown")
                } catch (_: Throwable) {}
            } else {
                Toast.makeText(context, "Failed to start web share: ${res.optString("error")}", Toast.LENGTH_LONG).show()
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e("WebShareScreen", "UnsatisfiedLinkError: ${e.message}", e)
            nativeError = "NATIVE LIB OUTDATED — run rebuildNative + reinstall"
        } catch (t: Throwable) {
            Log.e("WebShareScreen", "Error starting web share: ${t.message}", t)
            nativeError = t.message ?: "Failed to start web share"
        }
    }

    // Stage files in a background coroutine, then start the server.
    // Content-URI files are streamed to cache in 64 KB chunks so memory
    // stays under ~1 MB regardless of file size.
    LaunchedEffect(items) {
        withContext(Dispatchers.IO) {
            val stagingDir = File(context.cacheDir, "web-share-staging").apply { mkdirs() }
            val manifestArray = JSONArray()
            val totalBytes = items.sumOf { it.sizeBytes }
            var copiedSoFar = 0L

            for ((index, item) in items.withIndex()) {
                val shortName = if (item.displayName.length > 24) {
                    item.displayName.take(21) + "…"
                } else {
                    item.displayName
                }
                stagingLabel = "Preparing ${index + 1}/${items.size}: $shortName"

                val localPath = if (item.localFile != null && item.localFile.exists()) {
                    // File already on disk — no copy needed.
                    copiedSoFar += item.sizeBytes
                    if (totalBytes > 0) stagingProgress = copiedSoFar.toFloat() / totalBytes
                    item.localFile.absolutePath
                } else {
                    // Content URI — stream to cache in 64 KB chunks.
                    val dest = File(stagingDir, item.displayName)
                    try {
                        item.uri?.let { uri ->
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output ->
                                    val buf = ByteArray(65536)
                                    var n: Int
                                    while (input.read(buf).also { n = it } >= 0) {
                                        output.write(buf, 0, n)
                                        copiedSoFar += n
                                        if (totalBytes > 0) {
                                            stagingProgress = copiedSoFar.toFloat() / totalBytes
                                        }
                                    }
                                }
                            }
                        }
                        dest.absolutePath
                    } catch (e: Exception) {
                        Log.e("WebShareScreen", "Failed to stage ${item.displayName}: ${e.message}", e)
                        copiedSoFar += item.sizeBytes
                        if (totalBytes > 0) stagingProgress = copiedSoFar.toFloat() / totalBytes
                        item.uri?.path ?: ""
                    }
                }
                val obj = JSONObject().apply {
                    put("id", index)
                    put("name", item.displayName)
                    put("size", item.sizeBytes)
                    put("mime", item.mimeType ?: "application/octet-stream")
                    put("path", localPath)
                }
                manifestArray.put(obj)
            }

            val json = manifestArray.toString()
            manifestJsonStr = json
            stagingLabel = ""
            stagingProgress = 1f
        }

        // Back on the main dispatcher — start the server now that files are staged.
        startServerWithPin(if (isPinProtected) pinCode else null)
    }

    // Clean up on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                NxfrService.NxfrBridge.nxfr_web_stop()
            } catch (_: Throwable) {}
            val stagingDir = File(context.cacheDir, "web-share-staging")
            stagingDir.deleteRecursively()
        }
    }

    if (nativeError != null) {
        ErrorScreen(
            title = "NATIVE LIB OUTDATED",
            message = nativeError ?: "NATIVE LIB OUTDATED — run rebuildNative + reinstall",
            onBack = onStop
        )
        return
    }

    // 10-minute timer (only ticks once the server is running)
    LaunchedEffect(manifestJsonStr) {
        if (manifestJsonStr == null) return@LaunchedEffect
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
        onStop()
    }

    // ── Staging progress screen ─────────────────────────────────
    if (manifestJsonStr == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                progress = { stagingProgress.coerceIn(0f, 1f) },
                modifier = Modifier.size(64.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stagingLabel.ifEmpty { "Preparing files…" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { stagingProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${(stagingProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(onClick = onStop) {
                Text("Cancel")
            }
        }
        return
    }

    // ── Main share UI (server running) ──────────────────────────
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Share via Link",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$totalFiles file${if (totalFiles != 1) "s" else ""} • ${StagingRepository.formatBytes(totalSize)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // PIN Protection Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isPinProtected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = if (isPinProtected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Require Security PIN",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isPinProtected) "Recipients must enter PIN to download" else "Anyone with link can download directly",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isPinProtected,
                        onCheckedChange = { enabled ->
                            isPinProtected = enabled
                            startServerWithPin(if (enabled) pinCode else null)
                        }
                    )
                }

                if (isPinProtected) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "SECURITY PIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = pinCode.chunked(1).joinToString("  "),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        val newPin = generateRandomPin()
                                        pinCode = newPin
                                        startServerWithPin(newPin)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "Regenerate PIN",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        tempPinInput = pinCode
                                        showEditPinDialog = true
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Edit PIN",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // URL display card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (shareUrl.isNotEmpty()) {
                    Text(
                        text = shareUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    var isCopied by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val textToCopy = if (isPinProtected) {
                                "NXFR Share Link:\n$shareUrl\n\nSecurity PIN: $pinCode"
                            } else {
                                shareUrl
                            }
                            clipboard.setPrimaryClip(ClipData.newPlainText("NXFR Share Link", textToCopy))
                            Toast.makeText(context, if (isPinProtected) "Link & PIN copied to clipboard" else "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                            isCopied = true
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(1200)
                                isCopied = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isCopied) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isCopied) "Copied ✓" else if (isPinProtected) "Copy Link & PIN" else "Copy link")
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "⚠️ NO LOCAL NETWORK DETECTED",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Connect to Wi-Fi or enable Hotspot / Desert mode so other devices can reach this link.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // QR Code Card
        ElevatedCard(
            modifier = Modifier.size(200.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.White, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Share QR Code",
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Files Being Shared Card
        if (items.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "FILES AVAILABLE FOR DOWNLOAD (${items.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    items.take(4).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = StagingRepository.formatBytes(item.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (items.size > 4) {
                        Text(
                            text = "+ ${items.size - 4} more files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Expiry Countdown
        val mins = secondsRemaining / 60
        val secs = secondsRemaining % 60
        Text(
            text = String.format("Link expires in %02d:%02d", mins, secs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop sharing", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showEditPinDialog) {
        AlertDialog(
            onDismissRequest = { showEditPinDialog = false },
            title = { Text("Set Custom PIN") },
            text = {
                Column {
                    Text("Enter a 4 to 8 digit numeric security PIN for recipients:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempPinInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 8) {
                                tempPinInput = input
                            }
                        },
                        label = { Text("Security PIN") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempPinInput.length in 4..8) {
                            pinCode = tempPinInput
                            showEditPinDialog = false
                            startServerWithPin(tempPinInput)
                        } else {
                            Toast.makeText(context, "PIN must be 4 to 8 digits", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun generateRandomPin(): String {
    val num = (1000..9999).random()
    return num.toString()
}


private fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
