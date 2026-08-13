package com.nxfr.android.ui.sheets

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.service.NxfrService
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    var historyItems by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }

    fun loadHistory() {
        try {
            val storeDir = NxfrService.getIdentityDir(context)
            val jsonStr = NxfrService.NxfrBridge.nxfr_history_list(200, storeDir)
            val obj = JSONObject(jsonStr)
            val recordsArray = obj.optJSONArray("records") ?: return
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until recordsArray.length()) {
                val item = recordsArray.getJSONObject(i)
                val pathsArr = item.optJSONArray("file_paths")
                val pathsList = mutableListOf<String>()
                if (pathsArr != null) {
                    for (j in 0 until pathsArr.length()) {
                        pathsList.add(pathsArr.getString(j))
                    }
                }
                list.add(
                    HistoryItem(
                        id = item.optLong("id"),
                        tsMs = item.optLong("ts_ms"),
                        direction = item.optString("direction"),
                        peerName = item.optString("peer_name"),
                        peerId = item.optString("peer_id"),
                        fileCount = item.optInt("file_count"),
                        totalBytes = item.optLong("total_bytes"),
                        status = item.optString("status"),
                        filePaths = pathsList
                    )
                )
            }
            historyItems = list
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        loadHistory()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Transfer History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row {
                    if (historyItems.isNotEmpty()) {
                        IconButton(onClick = {
                            try {
                                val storeDir = NxfrService.getIdentityDir(context)
                                NxfrService.NxfrBridge.nxfr_history_clear(storeDir)
                                historyItems = emptyList()
                                Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear history")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (historyItems.isEmpty()) {
                // Empty state illustration + text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No transfers yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyItems, key = { it.id }) { item ->
                        HistoryRow(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    val context = LocalContext.current
    val isSend = item.direction == "send"

    val statusColor = when (item.status) {
        "complete" -> Color(0xFF00E5FF)
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
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
                    } catch (_: Exception) {
                        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "File no longer on device", Toast.LENGTH_SHORT).show()
                }
            },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(statusColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSend) Icons.AutoMirrored.Outlined.CallMade else Icons.AutoMirrored.Outlined.CallReceived,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.peerName.ifEmpty { "Unknown Peer" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatBytes(item.totalBytes)} • ${formatTimeAgo(item.tsMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                color = statusColor.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = item.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun formatTimeAgo(tsMs: Long): String {
    if (tsMs <= 0) return "Just now"
    val diff = System.currentTimeMillis() - tsMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
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
