package com.nxfr.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nxfr.android.R
import java.util.Locale

@Composable
fun TransferScreen(
    progress: Float = 0f,
    totalBytes: Long = 0L,
    fileName: String = "",
    isSending: Boolean = true,
    startTimeMillis: Long = System.currentTimeMillis(),
    transferredBytes: Long = 0L,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isComplete = progress >= 1.0f

    val currentTimeMillis = System.currentTimeMillis()
    val elapsedSeconds = (currentTimeMillis - startTimeMillis) / 1000.0
    
    val speedMbps = if (elapsedSeconds > 0) {
        (transferredBytes / (1024.0 * 1024.0)) / elapsedSeconds
    } else {
        0.0
    }

    val remainingBytes = totalBytes - transferredBytes
    val etaSeconds = if (speedMbps > 0) {
        (remainingBytes / (1024.0 * 1024.0)) / speedMbps
    } else {
        0.0
    }

    val speedText = String.format(Locale.getDefault(), "%.1f MB/s", speedMbps)
    val etaText = String.format(Locale.getDefault(), "ETA: %.0fs", etaSeconds)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSending) stringResource(R.string.transfer_sending) else stringResource(R.string.transfer_receiving),
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(text = fileName, style = MaterialTheme.typography.bodyLarge)
        
        Spacer(modifier = Modifier.height(16.dp))

        if (!isComplete) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "${(progress * 100).toInt()}%")
                Text(text = "$speedText • $etaText")
            }

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.transfer_cancel))
            }
        } else {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.transfer_complete),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.transfer_complete),
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
