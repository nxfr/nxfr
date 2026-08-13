package com.nxfr.android.ui.screens

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.R
import com.nxfr.android.service.NxfrService
import com.nxfr.android.service.NxfrState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TransferScreen(
    onCancel: () -> Unit = {},
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val nxfrState by NxfrService.nxfrState.collectAsState()
    val startTime = remember { System.currentTimeMillis() }
    val haptics = LocalHapticFeedback.current

    Log.d("TransferScreen", "Observed state: $nxfrState")

    // Auto-pop on complete after 1.5s with haptic burst
    LaunchedEffect(nxfrState) {
        if (nxfrState is NxfrState.Complete) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(1500)
            onComplete()
        }
    }
    
    androidx.activity.compose.BackHandler(onBack = onCancel)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state = nxfrState) {
            is NxfrState.Offering -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Waiting for approval",
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = state.peerName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Transferring -> {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMbpsRaw = if (elapsed > 0 && state.total > 0) {
                    (state.progress * state.total / (1024.0 * 1024.0)) / elapsed
                } else 0.0
                val remaining = state.total * (1.0 - state.progress)
                val eta = if (speedMbpsRaw > 0) remaining / (speedMbpsRaw * 1024 * 1024) else 0.0

                // Animated spring speed value
                val animatedSpeed by animateFloatAsState(
                    targetValue = speedMbpsRaw.toFloat(),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "AnimatedSpeed"
                )

                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress,
                    animationSpec = tween(durationMillis = 300, easing = LinearEasing),
                    label = "AnimatedProgress"
                )

                Text(
                    text = if (state.isSending) stringResource(R.string.transfer_sending)
                           else stringResource(R.string.transfer_receiving),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.fileName, 
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))

                // 200dp Circular Progress Ring with center stats
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f MB/s", animatedSpeed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "ETA: %.0fs", eta),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Complete -> {
                // Micro-celebration scale-in burst
                var targetScale by remember { mutableStateOf(0f) }
                val scale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "ScaleBurst"
                )

                LaunchedEffect(Unit) {
                    targetScale = 1.0f
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(200.dp)
                        .scale(scale)
                ) {
                    CircularProgressIndicator(
                        progress = { 1.0f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.transfer_complete),
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Complete ✓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            is NxfrState.Error -> {
                Text(
                    text = "Transfer Error",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Back")
                }
            }
            else -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                    Text("Connecting\u2026", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
