package com.nxfr.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nxfr.android.R

@Composable
fun TransferProgressScreen(
    fileName: String,
    fileSize: Long,
    progress: Float,
    speedBytesPerSec: Long,
    isSending: Boolean,
    isComplete: Boolean,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSending) "Sending to..." else "Receiving from...",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = fileName, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (isComplete) {
            Text(
                text = stringResource(R.string.transfer_complete),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Speed: ${speedBytesPerSec / 1024} KB/s")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCancel) {
                Text(stringResource(R.string.cancel_transfer))
            }
        }
    }
}
