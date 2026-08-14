package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nxfr.android.service.NxfrService
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun WebShareScreen(
    stagedItems: List<StagedItem>,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shareUrl by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var secondsRemaining by remember { mutableStateOf(600) } // 10 minutes
    val items = stagedItems

    val totalSize = remember(stagedItems) { StagingRepository.calculateTotalSize() }
    val totalFiles = remember(stagedItems) { StagingRepository.calculateTotalFiles() }

    var nativeError by remember { mutableStateOf<String?>(null) }

    // Start web share server on compose, stop on dispose
    DisposableEffect(Unit) {
        val stagingDir = File(context.cacheDir, "web-share-staging").apply { mkdirs() }
        val storeDir = NxfrService.getIdentityDir(context)

        // Build manifest array from staged items
        val manifestArray = JSONArray()
        for ((index, item) in items.withIndex()) {
            val localPath = if (item.localFile != null && item.localFile.exists()) {
                item.localFile.absolutePath
            } else {
                val dest = File(stagingDir, item.displayName)
                try {
                    item.uri?.let { uri ->
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    dest.absolutePath
                } catch (e: Exception) {
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

        try {
            val jsonStr = NxfrService.NxfrBridge.nxfr_web_share_start(17396, storeDir, "", manifestArray.toString())
            val res = JSONObject(jsonStr)

            if (res.optString("status") == "started") {
                val port = res.optInt("port", 17396)
                val token = res.optString("token", "")
                val ip = getDeviceIp(context)
                val url = "https://$ip:$port/#t=$token"
                shareUrl = url

                try {
                    val fpJson = NxfrService.NxfrBridge.nxfr_web_fingerprint(storeDir)
                    val fpObj = JSONObject(fpJson)
                    fingerprint = fpObj.optString("spki_sha256", "Unknown")
                } catch (_: Throwable) {}

                qrBitmap = generateQrBitmap(url)
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

        onDispose {
            try {
                NxfrService.NxfrBridge.nxfr_web_stop()
            } catch (_: Throwable) {}
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

    // 10-minute timer
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
        onStop()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
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

        // URL display card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = shareUrl.ifEmpty { "Starting share server..." },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NXFR Share Link", shareUrl))
                        Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    enabled = shareUrl.isNotEmpty()
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy link")
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Share QR Code",
                        modifier = Modifier.fillMaxSize()
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

        // Expiry Countdown
        val mins = secondsRemaining / 60
        val secs = secondsRemaining % 60
        Text(
            text = String.format("Link expires in %02d:%02d", mins, secs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop sharing")
        }
    }
}

private fun getDeviceIp(context: Context): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        val ips = mutableListOf<String>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    ips.add(addr.hostAddress ?: "")
                }
            }
        }
        return if (ips.isEmpty()) "127.0.0.1" else ips.first()
    } catch (_: Exception) {
        return "127.0.0.1"
    }
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
