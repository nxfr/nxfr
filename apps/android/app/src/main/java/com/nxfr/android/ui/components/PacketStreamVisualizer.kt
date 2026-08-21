package com.nxfr.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.AnimationPreference
import com.nxfr.android.ui.theme.LocalAnimationsEnabled
import com.nxfr.android.ui.theme.deckColors

@Composable
fun PacketStreamVisualizer(
    isSending: Boolean,
    progress: Float,
    speedMbps: Double,
    peerName: String = "PEER NODE",
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    val isAppAnimated = LocalAnimationsEnabled.current
    val isSystemDisabled = AnimationPreference.isSystemAnimationDisabled(context)
    val animationsEnabled = isAppAnimated && !isSystemDisabled

    // Fast packet cycle based on throughput (400ms at high speed down to 1200ms at low speed)
    val cycleDuration = if (speedMbps > 20.0) 400 else if (speedMbps > 5.0) 700 else 1100

    val infiniteTransition = rememberInfiniteTransition(label = "PacketStream")
    val animatedProgress = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PacketMotion"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Node Boxes and Transmission Beam
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                val currentProgress = animatedProgress.value
                val packet1Offset = if (animationsEnabled) currentProgress else 0.5f
                val packet2Offset = if (animationsEnabled) (currentProgress + 0.5f) % 1f else 0.8f

                val startX = 72.dp.toPx()
                val endX = size.width - 72.dp.toPx()
                val centerY = size.height / 2f

                // Base Laser Channel
                drawLine(
                    color = deck.signalBeam,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = 2.5.dp.toPx()
                )

                // Halo Glow
                drawLine(
                    color = deck.signalBeamGlow,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = 10.dp.toPx()
                )

                // Moving Packets
                if (animationsEnabled) {
                    val p1X = if (isSending) startX + (endX - startX) * packet1Offset else endX - (endX - startX) * packet1Offset
                    val p2X = if (isSending) startX + (endX - startX) * packet2Offset else endX - (endX - startX) * packet2Offset

                    drawCircle(
                        color = deck.signalBeam,
                        radius = 5.dp.toPx(),
                        center = Offset(p1X, centerY)
                    )
                    drawCircle(
                        color = deck.textPrimary,
                        radius = 3.dp.toPx(),
                        center = Offset(p2X, centerY)
                    )
                }
            }

            // Left & Right Node Monograms
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransferNodeBox(
                    title = "LOCAL",
                    sub = if (isSending) "TX SOURCE" else "RX SINK",
                    isHighlight = isSending
                )

                TransferNodeBox(
                    title = peerName.take(10).uppercase(),
                    sub = if (isSending) "RX SINK" else "TX SOURCE",
                    isHighlight = !isSending
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 16-Block Chunk Matrix
        val totalBlocks = 16
        val filledBlocks = (progress.coerceIn(0f, 1f) * totalBlocks).toInt()
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()

        val matrixText = buildString {
            append("[")
            for (i in 0 until totalBlocks) {
                if (i < filledBlocks) append("■") else append("□")
            }
            append("] $percent%")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BLOCK CHUNKS",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = deck.textSecondary
            )

            Text(
                text = matrixText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = deck.signalBeam
            )
        }
    }
}

@Composable
private fun TransferNodeBox(title: String, sub: String, isHighlight: Boolean) {
    val deck = MaterialTheme.deckColors
    Box(
        modifier = Modifier
            .width(80.dp)
            .background(deck.surface, RoundedCornerShape(2.dp))
            .border(1.dp, if (isHighlight) deck.signalBeam else deck.gridLine, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHighlight) deck.signalBeam else deck.textPrimary,
                maxLines = 1
            )
            Text(
                text = sub,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = deck.textDim
            )
        }
    }
}
