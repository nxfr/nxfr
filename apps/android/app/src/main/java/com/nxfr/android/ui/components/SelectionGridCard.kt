package com.nxfr.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.ui.icons.NxfrIcons

data class TileActionItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionGridCard(
    onOpenFilePicker: () -> Unit,
    onOpenMediaPicker: () -> Unit,
    onOpenTextComposer: () -> Unit,
    onPasteClipboard: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onOpenAppPicker: () -> Unit,
    onOpenContactsPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val tiles = listOf(
        TileActionItem("file", "File", NxfrIcons.File, onOpenFilePicker),
        TileActionItem("media", "Media", NxfrIcons.Media, onOpenMediaPicker),
        TileActionItem("text", "Text", Icons.Outlined.TextFields, onOpenTextComposer),
        TileActionItem("paste", "Paste", Icons.Outlined.ContentPaste, onPasteClipboard),
        TileActionItem("folder", "Folder", NxfrIcons.Folder, onOpenFolderPicker),
        TileActionItem("app", "App", NxfrIcons.App, onOpenAppPicker),
        TileActionItem("contacts", "Contacts", NxfrIcons.Contact, onOpenContactsPicker)
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Select Content to Send",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4
            ) {
                tiles.forEach { tile ->
                    Surface(
                        onClick = {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
                            tile.onClick()
                        },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        ) {
                            Icon(
                                tile.icon,
                                contentDescription = tile.label,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tile.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
