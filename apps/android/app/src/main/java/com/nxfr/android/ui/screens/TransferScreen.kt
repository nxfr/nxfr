package com.nxfr.android.ui.screens

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.R
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onCancel: () -> Unit = {},
    onComplete: () -> Unit = {},
    onSendAnother: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val nxfrState by NxfrService.nxfrState.collectAsState()
    val startTime = remember { System.currentTimeMillis() }
    val haptics = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var showCompleteSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }

    var lastPeerName by remember { mutableStateOf("Peer Device") }
    var lastFileName by remember { mutableStateOf("") }
    var lastTotalBytes by remember { mutableLongStateOf(0L) }
    var lastIsSending by remember { mutableStateOf(false) }
    var lastPublishedPath by remember { mutableStateOf<String?>(null) }
    var lastDurationSec by remember { mutableDoubleStateOf(0.0) }

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

                if (com.nxfr.android.prefs.NxfrPreferences.showChecksum.value && !state.filePath.isNullOrEmpty()) {
                    try {
                        val f = java.io.File(state.filePath)
                        if (f.exists() && f.length() < 50 * 1024 * 1024) {
                            val bytes = f.readBytes()
                            val jsonStr = NxfrService.NxfrBridge.nxfr_sha256(bytes)
                            val obj = org.json.JSONObject(jsonStr)
                            sha256Checksum = obj.optString("sha256")
                        }
                    } catch (_: Exception) {}
                }

                showCompleteSheet = true

                if (com.nxfr.android.prefs.NxfrPreferences.autoFinish.value) {
                    delay(1500)
                    showCompleteSheet = false
                    onComplete()
                }
            }
            else -> {}
        }
    }
    
    androidx.activity.compose.BackHandler(onBack = {
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state = nxfrState) {
            is NxfrState.Offering -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Waiting for approval",
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = state.peerName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Transferring -> {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMbpsRaw = if (elapsed > 0 && state.total > 0) {
                    (state.progress * state.total / (1024.0 * 1024.0)) / elapsed
                } else 0.0
                val remaining = state.total * (1.0 - state.progress)
                val eta = if (speedMbpsRaw > 0) remaining / (speedMbpsRaw * 1024 * 1024) else 0.0

                val animatedSpeed by animateFloatAsState(
                    targetValue = speedMbpsRaw.toFloat(),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "AnimatedSpeed"
                )

                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress,
                    animationSpec = tween(durationMillis = 300, easing = LinearEasing),
                    label = "AnimatedProgress"
                )

                Text(
                    text = if (state.isSending) stringResource(R.string.transfer_sending)
                           else stringResource(R.string.transfer_receiving),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.fileName, 
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f MB/s", animatedSpeed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "ETA: %.0fs", eta),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Complete -> {
                val isAnimationsEnabled = com.nxfr.android.ui.theme.LocalAnimationsEnabled.current
                var targetScale by remember { mutableStateOf(0f) }
                val animatedScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "ScaleBurst"
                )
                val scale = if (isAnimationsEnabled) animatedScale else 1.0f

                LaunchedEffect(Unit) {
                    targetScale = 1.0f
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(200.dp)
                        .scale(scale)
                ) {
                    CircularProgressIndicator(
                        progress = { 1.0f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.transfer_complete),
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Complete ✓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            is NxfrState.Error -> {
                ErrorScreen(
                    title = "Transfer Error",
                    message = state.msg,
                    onBack = onCancel
                )
            }
            else -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Text("Connecting\u2026", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (showCompleteSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showCompleteSheet = false
                onComplete()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = androidx.compose.ui.graphics.Color(0xFF00E5FF).copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Transfer Complete",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (lastIsSending) "Sent to $lastPeerName" else "Received from $lastPeerName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = lastFileName.ifEmpty { "1 file" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatBytes(lastTotalBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("•", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = String.format(Locale.getDefault(), "%.1fs", lastDurationSec),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text("•", style = MaterialTheme.typography.labelMedium)
                            val speed = if (lastDurationSec > 0) (lastTotalBytes / (1024.0 * 1024.0)) / lastDurationSec else 0.0
                            Text(
                                text = String.format(Locale.getDefault(), "%.1f MB/s", speed),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (!sha256Checksum.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            AssistChip(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", sha256Checksum))
                                    android.widget.Toast.makeText(context, "SHA-256 copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                label = {
                                    Text(
                                        text = "SHA-256: ${sha256Checksum!!.take(12)}…",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Checksum", modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (lastIsSending) {
                    Button(
                        onClick = {
                            showCompleteSheet = false
                            onSendAnother()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Another File")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showCompleteSheet = false
                            onComplete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                } else {
                    Button(
                        onClick = {
                            val path = lastPublishedPath ?: ""
                            if (path.isNotEmpty() && java.io.File(path).exists()) {
                                try {
                                    val file = java.io.File(path)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(context, "Cannot open file", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, "File no longer on device", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open File")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showCompleteSheet = false
                            onComplete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = {
                        showHistorySheet = true
                    }
                ) {
                    Text(
                        text = "View Transfer History",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    if (showHistorySheet) {
        com.nxfr.android.ui.sheets.HistorySheet(
            onDismiss = { showHistorySheet = false }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
