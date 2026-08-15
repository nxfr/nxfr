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
import com.nxfr.android.ui.icons.NxfrIcons

@Composable
fun StagingSummaryCard(
    onEditStaging: () -> Unit,
    onAddMore: () -> Unit,
    onClearStaging: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stagedItems by StagingRepository.stagedItems.collectAsState()

    if (stagedItems.isEmpty()) return

    val totalBytes = stagedItems.sumOf { it.sizeBytes }
    val formattedSize = StagingRepository.formatBytes(totalBytes)

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Staged for Transfer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${stagedItems.size} item(s) \u2022 $formattedSize",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                IconButton(onClick = onClearStaging) {
                    Icon(Icons.Default.Close, contentDescription = "Clear all", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                                StagedType.FILE -> NxfrIcons.File
                                StagedType.MEDIA -> NxfrIcons.Media
                                StagedType.TEXT -> Icons.Outlined.TextFields
                                StagedType.FOLDER -> NxfrIcons.Folder
                                StagedType.APP -> NxfrIcons.App
                                StagedType.CONTACT -> NxfrIcons.Contact
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
