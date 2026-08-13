package com.nxfr.android.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

    Log.d("TransferScreen", "Observed state: $nxfrState")

    // Auto-pop on complete after 1.5s.
    LaunchedEffect(nxfrState) {
        if (nxfrState is NxfrState.Complete) {
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
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Waiting for approval on ${state.peerName}\u2026",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Transferring -> {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMbps = if (elapsed > 0 && state.total > 0) {
                    (state.progress * state.total / (1024.0 * 1024.0)) / elapsed
                } else 0.0
                val remaining = state.total * (1.0 - state.progress)
                val eta = if (speedMbps > 0) remaining / (speedMbps * 1024 * 1024) else 0.0

                Text(
                    text = if (state.isSending) stringResource(R.string.transfer_sending)
                           else stringResource(R.string.transfer_receiving),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = state.fileName, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "${(state.progress * 100).toInt()}%")
                    Text(text = String.format(Locale.getDefault(), "%.1f MB/s \u2022 ETA: %.0fs", speedMbps, eta))
                }
                Spacer(modifier = Modifier.height(48.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
            is NxfrState.Complete -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.transfer_complete),
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Complete \u2713",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            is NxfrState.Error -> {
                Text(
                    text = "Error: ${state.msg}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Back")
                }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Connecting\u2026", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
