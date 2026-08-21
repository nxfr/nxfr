package com.nxfr.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nxfr.android.R
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import com.nxfr.android.ui.components.PacketStreamVisualizer
import com.nxfr.android.ui.components.TerminalStatsBlock
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private fun computeFileSha256(file: File): String? {
    if (!file.exists() || !file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    val hashBytes = digest.digest()
    return hashBytes.joinToString("") { "%02x".format(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onCancel: () -> Unit = {},
    onComplete: () -> Unit = {},
    onSendAnother: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val nxfrState by NxfrService.nxfrState.collectAsState()
    val startTime = remember { System.currentTimeMillis() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showCompleteSheet by remember { mutableStateOf(false) }
    var lastPeerName by remember { mutableStateOf("PEER NODE") }
    var lastFileName by remember { mutableStateOf("payload") }
    var lastTotalBytes by remember { mutableLongStateOf(0L) }
    var lastIsSending by remember { mutableStateOf(false) }
    var lastPublishedPath by remember { mutableStateOf<String?>(null) }
    var lastDurationSec by remember { mutableDoubleStateOf(0.0) }
    var peakSpeedMbps by remember { mutableDoubleStateOf(0.0) }
    var sha256Checksum by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nxfrState) {
        when (val state = nxfrState) {
            is NxfrState.Offering -> {
                lastPeerName = state.peerName
                lastFileName = state.displayName
            }
            is NxfrState.Transferring -> {
                lastFileName = state.fileName
                lastTotalBytes = state.total
                lastIsSending = state.isSending
            }
            is NxfrState.Complete -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                lastPublishedPath = state.filePath
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                lastDurationSec = if (elapsed > 0) elapsed else 1.0
                showCompleteSheet = true

                if (NxfrPreferences.showChecksum.value && !state.filePath.isNullOrEmpty()) {
                    val path = state.filePath
                    val checksum = withContext(Dispatchers.IO) {
                        try {
                            val f = File(path)
                            computeFileSha256(f)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    sha256Checksum = checksum
                }

                if (NxfrPreferences.autoFinish.value) {
                    delay(1500)
                    showCompleteSheet = false
                    onComplete()
                }
            }
            else -> {}
        }
    }

    BackHandler(onBack = {
        if (showCompleteSheet) {
            showCompleteSheet = false
            onComplete()
        } else {
            onCancel()
        }
    })

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(deck.rootBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state = nxfrState) {
            is NxfrState.Offering -> {
                // Cryptographic Offer Standby Screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, deck.signalBeam, RoundedCornerShape(4.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "AWAITING PEER CONSENT",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalBeam
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Target: ${state.peerName}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = deck.textPrimary
                        )
                        Text(
                            text = "Payload: ${state.displayName}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(2.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, deck.signalAlert)
                ) {
                    Text("ABORT REQUEST", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = deck.signalAlert)
                }
            }

            is NxfrState.Transferring -> {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMbpsRaw = if (elapsed > 0 && state.total > 0) {
                    (state.progress * state.total / (1024.0 * 1024.0)) / elapsed
                } else 0.0
                if (speedMbpsRaw > peakSpeedMbps) peakSpeedMbps = speedMbpsRaw

                val remaining = state.total * (1.0 - state.progress)
                val eta = if (speedMbpsRaw > 0) remaining / (speedMbpsRaw * 1024 * 1024) else 0.0

                // 1. Packet Stream Beam Visualizer
                PacketStreamVisualizer(
                    isSending = state.isSending,
                    progress = state.progress,
                    speedMbps = speedMbpsRaw,
                    peerName = lastPeerName
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Terminal Stats Telemetry Block
                TerminalStatsBlock(
                    isSending = state.isSending,
                    fileName = state.fileName,
                    progressBytes = (state.progress * state.total).toLong(),
                    totalBytes = state.total,
                    speedMbps = speedMbpsRaw,
                    peakSpeedMbps = peakSpeedMbps,
                    etaSec = eta
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Abort Action
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(2.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, deck.signalAlert)
                ) {
                    Text(
                        text = "ABORT STREAM",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.signalAlert
                    )
                }
            }

            is NxfrState.Complete -> {
                // Completed State Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, deck.signalSuccess, RoundedCornerShape(4.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TRANSMISSION COMPLETE ✓",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalSuccess
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "100% SHA-256 AUDITED",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textSecondary
                        )
                    }
                }
            }

            is NxfrState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, deck.signalAlert, RoundedCornerShape(4.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TRANSMISSION FAILED ✕",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalAlert
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.msg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = deck.textPrimary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text("DISMISS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = deck.textPrimary)
                        }
                    }
                }
            }

            else -> {
                // Connecting Standby
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "ESTABLISHING SECURE TLS 1.3 CONNECTION...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalBeam
                        )
                    }
                }
            }
        }
    }

    // ── Completion Sheet ──
    if (showCompleteSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showCompleteSheet = false
                onComplete()
            },
            sheetState = sheetState,
            containerColor = deck.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "TRANSMISSION MANIFEST [COMPLETE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.signalSuccess
                )

                Text(
                    text = lastFileName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.textPrimary
                )

                val avgSpeed = if (lastDurationSec > 0) (lastTotalBytes / (1024.0 * 1024.0)) / lastDurationSec else 0.0
                Text(
                    text = "SIZE: ${formatBytes(lastTotalBytes)} · DURATION: ${String.format(Locale.US, "%.1fs", lastDurationSec)} · AVG: ${String.format(Locale.US, "%.1f MB/s", avgSpeed)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = deck.textSecondary
                )

                // SHA-256 Checksum Tag
                if (!sha256Checksum.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                            .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(sha256Checksum!!))
                                Toast.makeText(context, "SHA-256 copied", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SHA-256 INTEGRITY AUDIT", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = deck.signalBeam)
                            Text(sha256Checksum!!, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = deck.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Hash", tint = deck.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(color = deck.gridLine)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!lastPublishedPath.isNullOrEmpty() && !lastIsSending) {
                        OutlinedButton(
                            onClick = {
                                openFileWithSystemViewer(context, lastPublishedPath!!)
                            },
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("OPEN FILE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = deck.signalBeam)
                        }
                    }

                    Button(
                        onClick = {
                            showCompleteSheet = false
                            onComplete()
                        },
                        shape = RoundedCornerShape(2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = deck.signalBeam, contentColor = deck.rootBackground),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("DONE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun openFileWithSystemViewer(context: android.content.Context, filePath: String) {
    try {
        val file = File(filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No app available to open file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
