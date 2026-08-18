package com.nxfr.android.ui.sheets

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nxfr.android.ui.theme.deckColors
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "HistorySheet"

data class HistoryItem(
    val id: Long,
    val tsMs: Long,
    val direction: String, // "send" or "recv"
    val peerName: String,
    val peerId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val status: String, // "complete", "failed", "rejected", "cancelled"
    val filePaths: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState()

    var historyItems by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    fun loadHistory() {
        try {
            val storeDir = NxfrService.getIdentityDir(context)
            val jsonStr = NxfrService.NxfrBridge.nxfr_history_list(200, storeDir)
            val obj = JSONObject(jsonStr)
            val recordsArray = obj.optJSONArray("records") ?: return
            val list = mutableListOf<HistoryItem>()

            for (i in 0 until recordsArray.length()) {
                try {
                    val item = recordsArray.getJSONObject(i)
                    val pathsArr = item.optJSONArray("file_paths")
                    val pathsList = mutableListOf<String>()
                    if (pathsArr != null) {
                        for (j in 0 until pathsArr.length()) {
                            val p = pathsArr.optString(j)
                            if (!p.isNullOrBlank()) {
                                pathsList.add(p)
                            }
                        }
                    }

                    val ts = if (item.has("ts_ms")) item.optLong("ts_ms") else item.optLong("timestamp_ms", System.currentTimeMillis())

                    list.add(
                        HistoryItem(
                            id = item.optLong("id", i.toLong()),
                            tsMs = ts,
                            direction = item.optString("direction", "recv"),
                            peerName = item.optString("peer_name", "Unknown Peer"),
                            peerId = item.optString("peer_id", ""),
                            fileCount = item.optInt("file_count", 1),
                            totalBytes = item.optLong("total_bytes", 0L),
                            status = item.optString("status", "complete"),
                            filePaths = pathsList
                        )
                    )
                } catch (rowErr: Throwable) {
                    // Skip malformed row gracefully
                    Log.w(TAG, "Skipping malformed history record at index $i: ${rowErr.message}")
                }
            }
            historyItems = list
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "UnsatisfiedLinkError loading history: ${e.message}", e)
            Toast.makeText(context, "NATIVE LIB OUTDATED — run rebuildNative + reinstall", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading history: ${t.message}", t)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            loadHistory()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = deck.surface,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(deck.gridLineBright, RoundedCornerShape(2.dp))
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // 1. Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = deck.signalBeam,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SESSION LEDGER [HISTORY]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (historyItems.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear ledger",
                                tint = deck.signalAlert,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = deck.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(deck.gridLine)
            )

            // 2. Content
            if (historyItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(deck.surfaceContainer, RoundedCornerShape(4.dp))
                            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = deck.textDim
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "NO SESSIONS LOGGED [STANDBY]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Transfers will automatically record here.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = deck.textDim
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyItems, key = { it.id }) { item ->
                        HistoryDeckRow(item = item)
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "PURGE SESSION LEDGER?",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will permanently delete all transfer history records from this device's local database.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = deck.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val storeDir = NxfrService.getIdentityDir(context)
                            NxfrService.NxfrBridge.nxfr_history_clear(storeDir)
                            historyItems = emptyList()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Ledger purged", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to clear history: ${t.message}", t)
                        }
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = deck.signalAlert)
                ) {
                    Text("PURGE ALL", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmDialog = false }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun HistoryDeckRow(item: HistoryItem) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val isSend = item.direction.equals("send", ignoreCase = true)

    val (statusLabel, statusColor) = when (item.status.lowercase()) {
        "complete", "completed" -> "COMPLETE" to deck.signalSuccess
        "rejected" -> "REJECTED" to deck.signalAlert
        "cancelled" -> "CANCELLED" to deck.textDim
        else -> "FAILED" to deck.signalAlert
    }

    val shortId = if (item.peerId.length >= 8) item.peerId.take(8) else item.peerName.hashCode().let {
        String.format(Locale.US, "%04x", Math.abs(it) % 0xFFFF)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(0.5.dp, deck.gridLine, RoundedCornerShape(2.dp))
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val path = item.filePaths.firstOrNull()
                if (path != null && File(path).exists()) {
                    try {
                        val file = File(path)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Cannot open file: ${e.message}", e)
                        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "PAYLOAD NO LONGER ON DEVICE", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: TX/RX Badge + Info
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Direction Badge
                Box(
                    modifier = Modifier
                        .background(deck.surface, RoundedCornerShape(2.dp))
                        .border(0.5.dp, if (isSend) deck.signalBeam else deck.textSecondary, RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSend) "TX ↗" else "RX ↙",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSend) deck.signalBeam else deck.textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = item.peerName.ifEmpty { "Unknown Station" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = deck.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "did:nxfr:$shortId · ${formatBytes(item.totalBytes)} · ${formatTimeAgo(item.tsMs)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = deck.textDim
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Status Pill
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                    .border(0.5.dp, statusColor, RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = statusLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = statusColor
                )
            }
        }
    }
}

private fun formatTimeAgo(tsMs: Long): String {
    if (tsMs <= 0) return "just now"
    val diff = System.currentTimeMillis() - tsMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        days < 30 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(tsMs))
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
