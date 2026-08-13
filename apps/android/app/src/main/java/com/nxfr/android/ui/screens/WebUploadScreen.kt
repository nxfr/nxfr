package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nxfr.android.R
import com.nxfr.android.service.NxfrService
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun WebUploadScreen(
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var uploadPort by remember { mutableIntStateOf(17396) }
    var uploadToken by remember { mutableStateOf("") }
    var isStarting by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDualModeAppQr by remember { mutableStateOf(false) }

    val deviceId by NxfrService.deviceId.collectAsState()

    DisposableEffect(Unit) {
        Log.i("WebUploadScreen", "Starting web upload server...")
        val storeDir = File(context.filesDir, "nxfr_identity").absolutePath
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
            val msg = "Native library outdated — rebuild APK"
            errorMessage = msg
            Log.e("WebUploadScreen", "JNI link error: ${e.message}", e)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e("WebUploadScreen", "Start parse exception: ${e.message}", e)
        }

        onDispose {
            Log.i("WebUploadScreen", "Stopping web upload server...")
            try {
                NxfrService.NxfrBridge.nxfr_web_stop()
            } catch (e: UnsatisfiedLinkError) {
                Log.e("WebUploadScreen", "JNI stop error: ${e.message}")
            }
        }
    }

    val primaryIp = remember { getPrimaryIp(context) }
    val webUrl = remember(primaryIp, uploadPort, uploadToken) {
        if (primaryIp.isNotEmpty() && uploadToken.isNotEmpty()) {
            "https://$primaryIp:$uploadPort/?t=$uploadToken#t=$uploadToken"
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
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = stringResource(R.string.receive_web_title),
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Text(
            text = stringResource(R.string.receive_web_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        if (errorMessage != null) {
            Text(
                text = "Error: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // URL Display Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isStarting) "Starting HTTPS server..." else webUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = {
                            if (webUrl.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NXFR Upload Link", webUrl))
                                Toast.makeText(context, context.getString(R.string.receive_web_link_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isStarting
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.receive_web_copy_link))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // QR Code Card with dual mode support (long-press to switch)
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
                    modifier = Modifier.fillMaxSize(),
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
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.receive_web_security_note),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(stringResource(R.string.receive_web_stop))
        }
    }
}

private fun getPrimaryIp(context: Context): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val ips = mutableListOf<Pair<String, String>>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    ips.add(iface.name to (addr.hostAddress ?: ""))
                }
            }
        }
        val best = ips.sortedByDescending { 
            it.first.startsWith("wlan") || it.first.startsWith("ap") 
        }.firstOrNull()
        return best?.second ?: ""
    } catch (_: Exception) {
        return ""
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
