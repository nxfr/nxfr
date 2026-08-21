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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PREFS_MAX_DOWNLOADS = "web_share_max_downloads"

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

    val prefs = remember { context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE) }
    var maxDownloads by remember { mutableStateOf(prefs.getString(PREFS_MAX_DOWNLOADS, "∞") ?: "∞") }

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

    fun startServerWithPin(pin: String?, maxDlStr: String = maxDownloads) {
        val manifest = manifestJsonStr ?: return
        val storeDir = NxfrService.getIdentityDir(context)
        val maxDownloadsInt = when (maxDlStr) {
            "1" -> 1
            "5" -> 5
            "10" -> 10
            else -> 0 // 0 = unlimited
        }
        try {
            NxfrService.NxfrBridge.nxfr_web_stop()
            val pinParam = pin ?: ""
            val jsonStr = NxfrService.NxfrBridge.nxfr_web_share_start(17396, storeDir, pinParam, manifest, maxDownloadsInt)
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
                    fingerprint = fpObj.optString("formatted", fpObj.optString("fingerprint", "Unknown"))
                } catch (_: Throwable) {}
            } else {
                Toast.makeText(context, "Failed to start web share: ${res.optString("error")}", Toast.LENGTH_LONG).show()
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e("WebShareScreen", "UnsatisfiedLinkError: ${e.message}", e)
            nativeError = "A required component is unavailable. Please update or reinstall NXFR."
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

    fun stopAndCleanup() {
        try {
            NxfrService.NxfrBridge.nxfr_web_stop()
        } catch (_: Throwable) {}
        val stagingDir = File(context.cacheDir, "web-share-staging")
        stagingDir.deleteRecursively()
        onStop()
    }

    if (nativeError != null) {
        ErrorScreen(
            title = "Component Unavailable",
            message = nativeError ?: "A required component is unavailable. Please update or reinstall NXFR.",
            onBack = { stopAndCleanup() }
        )
        return
    }

    var activeTransfers by remember { mutableIntStateOf(0) }
    var lastRecordedDownloads by remember { mutableIntStateOf(0) }

    // Approval gate state
    data class PendingRequestUi(
        val sessionId: String,
        val ip: String,
        val userAgent: String,
        val timestamp: Long
    )
    var pendingRequests by remember { mutableStateOf<List<PendingRequestUi>>(emptyList()) }
    var autoAcceptRequests by remember { mutableStateOf(prefs.getBoolean("web_share_auto_accept", false)) }

    // Sync auto-accept to native on start
    LaunchedEffect(manifestJsonStr, autoAcceptRequests) {
        if (manifestJsonStr == null) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                NxfrService.NxfrBridge.nxfr_web_set_auto_accept(autoAcceptRequests)
            }
        } catch (_: Throwable) {}
    }

    // 10-minute silence timer (defers shutdown while active transfers are in flight)
    LaunchedEffect(manifestJsonStr) {
        if (manifestJsonStr == null) return@LaunchedEffect
        while (true) {
            delay(1000)
            try {
                val statusJson = NxfrService.NxfrBridge.nxfr_web_status()
                val obj = org.json.JSONObject(statusJson)
                val isRunning = obj.optBoolean("running", true)
                val active = obj.optInt("active_transfers", 0)
                val dlCount = obj.optInt("download_count", 0)
                activeTransfers = active

                // M2: Record history when a new download completes
                if (dlCount > lastRecordedDownloads) {
                    val newCompleted = dlCount - lastRecordedDownloads
                    lastRecordedDownloads = dlCount
                    if (com.nxfr.android.prefs.NxfrPreferences.saveToHistory.value) {
                        for (i in 0 until newCompleted) {
                            val filePaths = items.mapNotNull { it.localFile?.absolutePath }
                            NxfrService.recordHistory(
                                context = context,
                                direction = "send",
                                peerName = "Web Browser",
                                peerId = "web-share",
                                fileCount = items.size.coerceAtLeast(1),
                                totalBytes = totalSize,
                                status = "complete",
                                filePaths = filePaths
                            )
                        }
                    }
                }

                // Parse pending requests
                val pendingArr = obj.optJSONArray("pending_requests")
                if (pendingArr != null) {
                    val reqs = mutableListOf<PendingRequestUi>()
                    for (i in 0 until pendingArr.length()) {
                        val r = pendingArr.getJSONObject(i)
                        reqs.add(PendingRequestUi(
                            sessionId = r.optString("session_id", ""),
                            ip = r.optString("ip", ""),
                            userAgent = r.optString("user_agent", ""),
                            timestamp = r.optLong("timestamp", 0)
                        ))
                    }
                    pendingRequests = reqs
                } else {
                    pendingRequests = emptyList()
                }

                if (!isRunning) {
                    stopAndCleanup()
                    break
                }

                if (active > 0) {
                    // Active transfer in flight — reset / defer silence countdown
                    secondsRemaining = 600
                } else {
                    if (secondsRemaining > 0) {
                        secondsRemaining--
                    } else {
                        stopAndCleanup()
                        break
                    }
                }
            } catch (_: Throwable) {
                if (secondsRemaining > 0) {
                    secondsRemaining--
                } else {
                    stopAndCleanup()
                    break
                }
            }
        }
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

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${(stagingProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(onClick = { stopAndCleanup() }) {
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

        // ── Max Downloads Quota ──
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.deckColors.gridLineBright),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.deckColors.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "DOWNLOAD QUOTA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.deckColors.signalBeam,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Auto-shut down the share link after N downloads. 0 = unlimited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.deckColors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("1×", "5×", "10×", "∞").forEach { label ->
                        val isSelected = when (label) {
                            "1×" -> maxDownloads == "1"
                            "5×" -> maxDownloads == "5"
                            "10×" -> maxDownloads == "10"
                            "∞" -> maxDownloads == "∞" || maxDownloads == "0"
                            else -> false
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val newVal = when (label) {
                                    "1×" -> "1"
                                    "5×" -> "5"
                                    "10×" -> "10"
                                    else -> "∞"
                                }
                                maxDownloads = newVal
                                prefs.edit().putString(PREFS_MAX_DOWNLOADS, newVal).apply()
                                startServerWithPin(if (isPinProtected) pinCode else null, newVal)
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.deckColors.rootBackground
                                            else MaterialTheme.deckColors.textSecondary
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.deckColors.signalBeam,
                                selectedLabelColor = MaterialTheme.deckColors.rootBackground,
                                containerColor = MaterialTheme.deckColors.surfaceVariant
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        CertWarningCard(context)

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

        // ── REQUESTS SECTION ────────────────────────────────────────
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.deckColors.gridLineBright),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.deckColors.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REQUESTS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.deckColors.signalBeam
                    )
                    if (pendingRequests.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.deckColors.signalBeam
                        ) {
                            Text(
                                text = "${pendingRequests.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.deckColors.rootBackground
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (pendingRequests.isEmpty()) {
                    Text(
                        text = if (autoAcceptRequests) "Auto-accepting all requests" else "No requests yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textSecondary
                    )
                } else {
                    pendingRequests.forEach { req ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.deckColors.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Browser name + IP
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = parseBrowserName(req.userAgent),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.deckColors.signalWarning
                                    )
                                    Text(
                                        text = req.ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.deckColors.textSecondary
                                    )
                                }
                                // Reject button
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                NxfrService.NxfrBridge.nxfr_web_respond_request(req.sessionId, false)
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Reject",
                                        tint = MaterialTheme.deckColors.signalAlert
                                    )
                                }
                                // Accept button
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                NxfrService.NxfrBridge.nxfr_web_respond_request(req.sessionId, true)
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = "Accept",
                                        tint = MaterialTheme.deckColors.signalSuccess
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.deckColors.gridLineBright)
                Spacer(modifier = Modifier.height(8.dp))

                // Auto-accept toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Automatically accept requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textPrimary
                    )
                    Switch(
                        checked = autoAcceptRequests,
                        onCheckedChange = { enabled ->
                            autoAcceptRequests = enabled
                            prefs.edit().putBoolean("web_share_auto_accept", enabled).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.deckColors.signalBeam,
                            checkedTrackColor = MaterialTheme.deckColors.gridLineBright
                        )
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

        // Expiry Countdown / Active Transfer Status
        if (activeTransfers > 0) {
            Text(
                text = "TRANSFER ACTIVE — auto-stop deferred",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        } else {
            val mins = secondsRemaining / 60
            val secs = secondsRemaining % 60
            Text(
                text = String.format("Link expires in %02d:%02d of inactivity", mins, secs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (maxDownloads == "∞" || maxDownloads == "0")
                "Quota: Unlimited downloads"
            else
                "Quota: $maxDownloads download${if (maxDownloads == "1") "" else "s"} max",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.deckColors.textSecondary,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { stopAndCleanup() },
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

/** Extracts a human-readable browser name from a User-Agent string. */
private fun parseBrowserName(ua: String): String {
    if (ua.isBlank()) return "Unknown Browser"
    val lower = ua.lowercase()

    val browser = when {
        lower.contains("edg/") || lower.contains("edge/") -> "Edge"
        lower.contains("opr/") || lower.contains("opera") -> "Opera"
        lower.contains("brave") -> "Brave"
        lower.contains("vivaldi") -> "Vivaldi"
        lower.contains("firefox") || lower.contains("fxios") -> "Firefox"
        lower.contains("crios") -> "Chrome"
        lower.contains("safari") && !lower.contains("chrome") -> "Safari"
        lower.contains("chrome") -> "Chrome"
        else -> "Browser"
    }

    val os = when {
        lower.contains("iphone") || lower.contains("ipad") -> "iOS"
        lower.contains("android") -> "Android"
        lower.contains("mac os") || lower.contains("macintosh") -> "macOS"
        lower.contains("windows") -> "Windows"
        lower.contains("linux") -> "Linux"
        lower.contains("cros") -> "ChromeOS"
        else -> null
    }

    return if (os != null) "$browser ($os)" else browser
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

@Composable
fun CertWarningCard(context: Context) {
    val prefs = context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE)
    var isExpanded by remember { mutableStateOf(!prefs.getBoolean("cert_warning_dismissed", false)) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.deckColors.gridLineBright)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.deckColors.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isExpanded = !isExpanded
                        prefs.edit().putBoolean("cert_warning_dismissed", !isExpanded).apply()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⚠️ Browser certificate warning — tap for help",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.deckColors.signalBeam,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.deckColors.textSecondary
                )
            }
            
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.deckColors.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "This device uses a self-signed TLS certificate. Your browser will show a warning — this is normal for local transfers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• Chrome / Edge\n  Tap \"Advanced\" → \"Proceed to [IP] (unsafe)\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Safari (iOS)\n  Tap \"Show Details\" → \"Visit this website\" → Confirm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Firefox\n  Tap \"Advanced\" → \"Accept the Risk and Continue\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.deckColors.textPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
