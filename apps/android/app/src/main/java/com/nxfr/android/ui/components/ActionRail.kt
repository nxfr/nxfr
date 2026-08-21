package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.icons.NxfrIcons
import com.nxfr.android.ui.theme.deckColors

@Composable
fun ActionRail(
    isPowered: Boolean,
    onReceiveViaLink: () -> Unit,
    onScanQr: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDesert: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionDeckChip(
            icon = NxfrIcons.WebLink,
            label = "WEB LINK",
            enabled = isPowered,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onReceiveViaLink()
            },
            modifier = Modifier.weight(1f)
        )

        ActionDeckChip(
            icon = NxfrIcons.QrScan,
            label = "SCAN QR",
            enabled = true,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onScanQr()
            },
            modifier = Modifier.weight(1f)
        )

        ActionDeckChip(
            icon = NxfrIcons.Diagnostics,
            label = "DIAGNOSTICS",
            enabled = true,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpenDiagnostics()
            },
            modifier = Modifier.weight(1f)
        )

        ActionDeckChip(
            icon = Icons.Outlined.WifiTethering,
            label = "DESERT",
            enabled = true,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpenDesert()
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionDeckChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(
                if (enabled) deck.surface else deck.surfaceContainer,
                RoundedCornerShape(3.dp)
            )
            .border(
                1.dp,
                if (enabled) deck.gridLineBright else deck.gridLine,
                RoundedCornerShape(3.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) deck.signalBeam else deck.textDim,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) deck.textPrimary else deck.textDim
            )
        }
    }
}
