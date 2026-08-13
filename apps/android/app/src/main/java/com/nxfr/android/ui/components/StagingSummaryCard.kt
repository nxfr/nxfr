package com.nxfr.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository

@Composable
fun StagingSummaryCard(
    onEditStaging: () -> Unit,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stagedItems by StagingRepository.stagedItems.collectAsState()
    val totalFiles = StagingRepository.calculateTotalFiles()
    val totalSize = StagingRepository.calculateTotalSize()

    if (stagedItems.isEmpty()) return

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Staged: $totalFiles item${if (totalFiles != 1) "s" else ""} • ${StagingRepository.formatBytes(totalSize)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = { StagingRepository.clear() }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear all", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Thumbnail / Icon Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(stagedItems, key = { it.id }) { item ->
                    AssistChip(
                        onClick = onEditStaging,
                        label = { Text(item.displayName, maxLines = 1) },
                        leadingIcon = {
                            val icon = when (item.type) {
                                StagedType.FILE -> Icons.Outlined.InsertDriveFile
                                StagedType.MEDIA -> Icons.Outlined.Image
                                StagedType.TEXT -> Icons.Outlined.TextFields
                                StagedType.FOLDER -> Icons.Outlined.Folder
                                StagedType.APP -> Icons.Outlined.Apps
                                StagedType.CONTACT -> Icons.Outlined.Contacts
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onAddMore) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onEditStaging) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit selection")
                }
            }
        }
    }
}
