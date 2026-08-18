package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.sheets.HistoryItem
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

@Composable
fun RecentSessionsCard(
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    val isHistoryEnabled by NxfrPreferences.saveToHistory.collectAsState()
    var recentItems by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }

    LaunchedEffect(isHistoryEnabled) {
        if (isHistoryEnabled) {
            withContext(Dispatchers.IO) {
                try {
                    val storeDir = NxfrService.getIdentityDir(context)
                    val jsonStr = NxfrService.NxfrBridge.nxfr_history_list(3, storeDir)
                    val obj = JSONObject(jsonStr)
                    val recordsArray = obj.optJSONArray("records")
                    if (recordsArray != null) {
                        val list = mutableListOf<HistoryItem>()
                        for (i in 0 until minOf(3, recordsArray.length())) {
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
                                    direction = item.optString("direction", "send"),
                                    peerName = item.optString("peer_name", "Peer"),
                                    peerId = item.optString("peer_id", ""),
                                    fileCount = item.optInt("file_count", 1),
                                    totalBytes = item.optLong("total_bytes", 0L),
                                    status = item.optString("status", "complete"),
                                    filePaths = pathsList
                                )
                            )
                        }
                        recentItems = list
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(deck.surface, RoundedCornerShape(4.dp))
            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
            .padding(14.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENT SESSIONS (${recentItems.size})",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = deck.textSecondary
            )

            Text(
                text = "HISTORY →",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = deck.signalBeam,
                modifier = Modifier
                    .clickable(onClick = onOpenHistory)
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!isHistoryEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "HISTORY DISABLED [OFFLINE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = deck.textDim
                )
            }
        } else if (recentItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "NO RECENT SESSIONS [READY]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = deck.textDim
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentItems.forEach { item ->
                    SessionLedgerRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun SessionLedgerRow(item: HistoryItem) {
    val deck = MaterialTheme.deckColors
    val isSend = item.direction == "send"
    val isComplete = item.status.equals("complete", ignoreCase = true) || item.status.equals("completed", ignoreCase = true)

    val displayName = item.filePaths.firstOrNull()?.substringAfterLast('/') ?: "Payload #${item.id}"
    val sizeStr = formatBytes(item.totalBytes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(0.5.dp, deck.gridLine, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TX / RX Direction Badge
            Box(
                modifier = Modifier
                    .background(if (isSend) deck.surfaceVariant else deck.surface, RoundedCornerShape(2.dp))
                    .border(1.dp, if (isSend) deck.signalBeam else deck.gridLineBright, RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSend) "TX" else "RX",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSend) deck.signalBeam else deck.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = deck.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.peerName} · $sizeStr",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = deck.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status Pill
        Text(
            text = if (isComplete) "100% ✓" else "FAILED ✕",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isComplete) deck.signalSuccess else deck.signalAlert
        )
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
