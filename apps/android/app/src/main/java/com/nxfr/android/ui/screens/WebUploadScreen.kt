package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nxfr.android.R
import com.nxfr.android.discovery.NetworkInterfaceHelper
import com.nxfr.android.service.NxfrService
import com.nxfr.android.staging.StagingRepository
import com.nxfr.android.storage.FilePublisher
import com.nxfr.android.transfer.TransferNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ReceivedWebFile(
    val name: String,
    val sizeBytes: Long,
    val publishedPath: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun WebUploadScreen(
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var uploadPort by remember { mutableIntStateOf(17396) }
    var uploadToken by remember { mutableStateOf("") }
    var isStarting by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDualModeAppQr by remember { mutableStateOf(false) }
    val receivedFiles = remember { mutableStateListOf<ReceivedWebFile>() }

    val deviceId by NxfrService.deviceId.collectAsState()
    val notificationManager = remember { TransferNotificationManager(context) }

    DisposableEffect(Unit) {
        Log.i("WebUploadScreen", "Starting web upload server...")
        val storeDir = NxfrService.getIdentityDir(context)
        try {
            val resultJson = NxfrService.NxfrBridge.nxfr_web_start(17396, storeDir, "")
            val json = JSONObject(resultJson)
            if (json.has("error")) {
                errorMessage = json.getString("error")
                Log.e("WebUploadScreen", "Start failed: $errorMessage")
            } else {
                uploadPort = json.optInt("port", 17396)
                uploadToken = json.optString("token", "")
                isStarting = false
                Log.i("WebUploadScreen", "Started on port $uploadPort, token $uploadToken")
            }
        } catch (e: UnsatisfiedLinkError) {
            val msg = "NATIVE LIB OUTDATED — run rebuildNative + reinstall"
            errorMessage = msg
            Log.e("WebUploadScreen", "JNI link error: ${e.message}", e)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            errorMessage = t.message ?: "Failed to start web upload server"
            Log.e("WebUploadScreen", "Start error: ${t.message}", t)
        }

        onDispose {
            Log.i("WebUploadScreen", "Stopping web upload server...")
            try {
                NxfrService.NxfrBridge.nxfr_web_stop()
            } catch (_: Throwable) {}
        }
    }

    // Live inbox poller to detect uploaded files, publish to Downloads, and notify
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                try {
                    val inboxDirs = NxfrService.getWebInboxDirs(context)
                    for (inboxDir in inboxDirs) {
                        val files = inboxDir.listFiles() ?: continue
                        for (file in files) {
                            if (file.isFile && !file.name.endsWith(".tmp") && file.length() > 0) {
                                val originalName = file.name
                                val size = file.length()
                                val publishedPath = FilePublisher.publishToDownloads(context, file)

                                NxfrService.recordHistory(
                                    context = context,
                                    direction = "recv",
                                    peerName = "Web Browser",
                                    peerId = "web-upload",
                                    fileCount = 1,
                                    totalBytes = size,
                                    status = "completed",
                                    filePaths = listOf(publishedPath)
                                )

                                withContext(Dispatchers.Main) {
                                    notificationManager.showTransferCompleteNotification(
                                        transferId = (originalName.hashCode() and 0x7FFFFFFF),
                                        fileName = originalName,
                                        fileSize = size,
                                        publishedPath = publishedPath,
                                        isSending = false,
                                        peerName = "Web Browser"
                                    )
                                    receivedFiles.add(
                                        0,
                                        ReceivedWebFile(
                                            name = originalName,
                                            sizeBytes = size,
                                            publishedPath = publishedPath
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("WebUploadScreen", "Inbox poll error: ${e.message}")
                }
                delay(800)
            }
        }
    }

    val primaryIp = remember { NetworkInterfaceHelper.getPrimaryLocalIp(context) ?: "" }
    val webUrl = remember(primaryIp, uploadPort, uploadToken) {
        if (primaryIp.isNotEmpty() && uploadToken.isNotEmpty()) {
            "https://$primaryIp:$uploadPort/#t=$uploadToken"
        } else ""
    }

    val appTicketUrl = remember(primaryIp, deviceId) {
        if (primaryIp.isNotEmpty() && deviceId.isNotEmpty()) {
            "nxfr://connect?did=$deviceId&addr=$primaryIp:17394"
        } else ""
    }

    val qrContent = if (isDualModeAppQr) appTicketUrl else webUrl
    val qrBitmap = remember(qrContent) { generateQrBitmap(qrContent) }

    if (errorMessage != null) {
        ErrorScreen(
            title = "Web Upload Error",
            message = errorMessage!!,
            onBack = onStop
        )
        return
    }

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
            text = stringResource(R.string.receive_web_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = stringResource(R.string.receive_web_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // URL Display Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (webUrl.isNotEmpty()) {
                    Text(
                        text = if (isStarting) "Starting HTTPS server..." else webUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    var isCopied by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("NXFR Upload Link", webUrl))
                            Toast.makeText(context, context.getString(R.string.receive_web_link_copied), Toast.LENGTH_SHORT).show()
                            isCopied = true
                            coroutineScope.launch {
                                delay(1200)
                                isCopied = false
                            }
                        },
                        enabled = !isStarting
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isCopied) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isCopied) "Link Copied ✓" else stringResource(R.string.receive_web_copy_link))
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
                                text = "Connect to Wi-Fi or enable Hotspot / Desert mode so other devices can upload files.",
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
        
        // QR Code Card with dual mode support (tap to switch)
        ElevatedCard(
            modifier = Modifier
                .size(200.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isDualModeAppQr = !isDualModeAppQr
                        },
                        onLongPress = {
                            isDualModeAppQr = !isDualModeAppQr
                            val modeStr = if (isDualModeAppQr) "App Ticket (nxfr://)" else "Web HTTPS URL"
                            Toast.makeText(context, "QR switched to: $modeStr", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
            shape = MaterialTheme.shapes.medium
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isDualModeAppQr) "Mode: App Connection Ticket (Tap to switch)" else "Mode: Web Upload Link (Tap/Hold to switch)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        // Live Received Files Section
        if (receivedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECEIVED FILES (${receivedFiles.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Saved to Downloads/NXFR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    receivedFiles.forEach { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = StagingRepository.formatBytes(item.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = { openPublishedFile(context, item.publishedPath) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Open", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        var webFingerprintFormatted by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            try {
                val storeDir = NxfrService.getIdentityDir(context)
                val jsonStr = NxfrService.NxfrBridge.nxfr_web_fingerprint(storeDir)
                val obj = JSONObject(jsonStr)
                webFingerprintFormatted = obj.optString("formatted", obj.optString("fingerprint", ""))
            } catch (_: Throwable) {}
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Browser shows a security warning? Normal for direct links. Tap Advanced → Proceed — files stay encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (webFingerprintFormatted.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHA-256 Fingerprint (SPKI):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = webFingerprintFormatted,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("NXFR Fingerprint", webFingerprintFormatted))
                            Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy fingerprint", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.receive_web_security_note),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(stringResource(R.string.receive_web_stop), fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun openPublishedFile(context: Context, pathOrUri: String) {
    try {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("NXFR")
        val file = if (pathOrUri.startsWith("/")) File(pathOrUri) else File(publicDir, File(pathOrUri).name)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Saved to Downloads/NXFR", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    if (content.isEmpty()) return null
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
