package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.discovery.DeviceUiModel
import com.nxfr.android.ui.theme.deckColors

@Composable
fun DeviceDeckCard(
    device: DeviceUiModel,
    isQueued: Boolean = false,
    isMultipleMode: Boolean = false,
    onClick: () -> Unit,
    onPairClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val shortId = if (device.deviceId.length >= 4) device.deviceId.take(4) else device.deviceId

    val cardBorder = if (isQueued) deck.signalBeam else deck.gridLine
    val cardBackground = if (isQueued) deck.surfaceVariant else deck.surface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cardBackground, RoundedCornerShape(4.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "Node ${device.name}, ID #$shortId, IP ${device.host}" }
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Column: Name, Address, and Protocol Pipes
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = deck.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "#$shortId",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = deck.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${device.host}:${device.port}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.signalBeam
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Status Badges Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BadgePill(text = "17394/TLS", isHighlight = false)

                if (device.isPaired) {
                    BadgePill(text = "PAIRED", isHighlight = true)
                } else if (onPairClick != null) {
                    Box(
                        modifier = Modifier
                            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                            .border(0.5.dp, deck.signalBeam, RoundedCornerShape(2.dp))
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPairClick()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PAIR ⚡",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalBeam
                        )
                    }
                } else {
                    BadgePill(text = "TOFU", isHighlight = false)
                }

                if (device.isDirect) {
                    BadgePill(text = "📡 DIRECT", isHighlight = true)
                }

                if (isMultipleMode && isQueued) {
                    BadgePill(text = "QUEUED", isHighlight = true)
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Right Action Badge
        Box(
            modifier = Modifier
                .background(
                    if (isQueued) deck.signalBeam else deck.surfaceContainer,
                    RoundedCornerShape(2.dp)
                )
                .border(
                    1.dp,
                    if (isQueued) deck.signalBeam else deck.gridLineBright,
                    RoundedCornerShape(2.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isMultipleMode) {
                    if (isQueued) "✓ QUEUED" else "+ QUEUE"
                } else {
                    "TX →"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isQueued) deck.rootBackground else deck.signalBeam
            )
        }
    }
}

@Composable
private fun BadgePill(text: String, isHighlight: Boolean) {
    val deck = MaterialTheme.deckColors
    Box(
        modifier = Modifier
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(
                0.5.dp,
                if (isHighlight) deck.signalSuccess else deck.gridLineBright,
                RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHighlight) deck.signalSuccess else deck.textDim
        )
    }
}
