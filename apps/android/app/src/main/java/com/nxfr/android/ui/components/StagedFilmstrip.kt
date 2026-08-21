package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.nxfr.android.ui.icons.NxfrIcons
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.ui.theme.deckColors
import java.util.Locale

@Composable
fun StagedFilmstrip(
    stagedItems: List<StagedItem>,
    onRemoveItem: (StagedItem) -> Unit,
    onClearAll: () -> Unit,
    onOpenEditSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (stagedItems.isEmpty()) return

    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val totalBytes = stagedItems.sumOf { it.sizeBytes }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(deck.surface, RoundedCornerShape(4.dp))
            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
            .padding(12.dp)
    ) {
        // Top Manifest Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "STAGED: ${stagedItems.size} ${if (stagedItems.size == 1) "ITEM" else "ITEMS"} · ${formatBytes(totalBytes)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = deck.signalBeam
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "EDIT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.textSecondary,
                    modifier = Modifier
                        .clickable(onClick = onOpenEditSheet)
                        .padding(2.dp)
                )

                Text(
                    text = "CLEAR ALL",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.signalAlert,
                    modifier = Modifier
                        .clickable(onClick = onClearAll)
                        .padding(2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Horizontal Filmstrip Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stagedItems.forEach { item ->
                StagedItemCard(
                    item = item,
                    onRemove = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRemoveItem(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun StagedItemCard(
    item: StagedItem,
    onRemove: () -> Unit
) {
    val deck = MaterialTheme.deckColors
    val icon = when (item.type) {
        StagedType.FILE -> NxfrIcons.File
        StagedType.MEDIA -> NxfrIcons.Media
        StagedType.TEXT -> Icons.Outlined.TextFields
        StagedType.FOLDER -> NxfrIcons.Folder
        StagedType.APP -> NxfrIcons.App
        StagedType.CONTACT -> NxfrIcons.Contact
    }

    Box(
        modifier = Modifier
            .width(130.dp)
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
            .padding(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = deck.signalBeam,
                    modifier = Modifier.size(16.dp)
                )

                // Angular Remove Glyph
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(20.dp)
                        .semantics { contentDescription = "Remove ${item.displayName}" }
                        .clickable(role = Role.Button, onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.displayName,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = deck.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formatBytes(item.sizeBytes),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = deck.textSecondary
            )
        }
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
