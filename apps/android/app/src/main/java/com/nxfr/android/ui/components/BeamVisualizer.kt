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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.AnimationPreference
import com.nxfr.android.ui.theme.LocalAnimationsEnabled
import com.nxfr.android.ui.theme.deckColors

@Composable
fun BeamVisualizer(
    isPowered: Boolean,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    val isAppAnimated = LocalAnimationsEnabled.current
    val isSystemDisabled = AnimationPreference.isSystemAnimationDisabled(context)
    val animationsEnabled = isAppAnimated && !isSystemDisabled

    val infiniteTransition = rememberInfiniteTransition(label = "BeamAnimation")

    val animatedSweep = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPowered) 900 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepProgress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background Wire & Traveling Packet Dots
            Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                val sweepProgress = if (animationsEnabled) animatedSweep.value else if (isPowered) 0.5f else 0.0f
                val startX = 68.dp.toPx()
                val endX = size.width - 68.dp.toPx()
                val centerY = size.height / 2f

                val wireColor = if (isPowered) deck.signalBeam else deck.signalStandby
                val wireAlpha = if (isPowered) 0.95f else 0.4f

                // Base Transmission Line
                drawLine(
                    color = wireColor.copy(alpha = wireAlpha),
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = if (isPowered) 2.5.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (!isPowered) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
                )

                // Outer Beam Glow when powered
                if (isPowered) {
                    drawLine(
                        color = deck.signalBeamGlow,
                        start = Offset(startX, centerY),
                        end = Offset(endX, centerY),
                        strokeWidth = 8.dp.toPx()
                    )
                }

                // Packet Dot / Scan Marker
                if (animationsEnabled || isPowered) {
                    val packetX = startX + (endX - startX) * sweepProgress
                    drawCircle(
                        color = if (isPowered) deck.signalBeam else deck.textSecondary,
                        radius = if (isPowered) 4.5.dp.toPx() else 2.5.dp.toPx(),
                        center = Offset(packetX, centerY)
                    )
                }
            }

            // Node Badges at Left and Right Edges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NodeBadge(label = "LOCAL", isPowered = isPowered)
                NodeBadge(label = if (isPowered) "BROADCAST" else "STANDBY", isPowered = isPowered)
            }
        }
    }
}

@Composable
private fun NodeBadge(label: String, isPowered: Boolean) {
    val deck = MaterialTheme.deckColors
    Box(
        modifier = Modifier
            .background(deck.surface, RoundedCornerShape(2.dp))
            .border(1.dp, if (isPowered) deck.signalBeam else deck.gridLine, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPowered) deck.signalBeam else deck.textDim
        )
    }
}
