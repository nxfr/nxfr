package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.icons.NxfrIcons
import com.nxfr.android.ui.theme.deckColors

@Composable
fun AttachChipRail(
    onOpenFilePicker: () -> Unit,
    onOpenMediaPicker: () -> Unit,
    onOpenTextComposer: () -> Unit,
    onPasteClipboard: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onOpenAppPicker: () -> Unit,
    onOpenContactsPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    val chips = listOf(
        AttachChipData("FILE", NxfrIcons.File, onOpenFilePicker),
        AttachChipData("MEDIA", NxfrIcons.Media, onOpenMediaPicker),
        AttachChipData("TEXT", Icons.Outlined.TextFields, onOpenTextComposer),
        AttachChipData("PASTE", Icons.Outlined.ContentPaste, onPasteClipboard),
        AttachChipData("FOLDER", NxfrIcons.Folder, onOpenFolderPicker),
        AttachChipData("APP", NxfrIcons.App, onOpenAppPicker),
        AttachChipData("CONTACT", NxfrIcons.Contact, onOpenContactsPicker)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chip ->
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .background(deck.surface, RoundedCornerShape(2.dp))
                    .border(1.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                    .semantics { contentDescription = "Attach ${chip.label}" }
                    .clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        chip.onClick()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = null,
                        tint = deck.signalBeam,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+ ${chip.label}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textPrimary
                    )
                }
            }
        }
    }
}

private data class AttachChipData(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
