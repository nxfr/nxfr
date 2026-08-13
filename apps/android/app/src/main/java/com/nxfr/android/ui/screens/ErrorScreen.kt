package com.nxfr.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nxfr.android.R

enum class ErrorType {
    NETWORK_DISCONNECTED,
    STORAGE_FULL,
    SECURITY_REJECTED,
    GENERIC
}

fun mapErrorMessageToType(msg: String): ErrorType {
    val lower = msg.lowercase()
    return when {
        lower.contains("network") || lower.contains("wifi") || lower.contains("connection") || lower.contains("timeout") || lower.contains("socket") -> ErrorType.NETWORK_DISCONNECTED
        lower.contains("storage") || lower.contains("space") || lower.contains("full") || lower.contains("permission") -> ErrorType.STORAGE_FULL
        lower.contains("security") || lower.contains("auth") || lower.contains("reject") || lower.contains("unpair") -> ErrorType.SECURITY_REJECTED
        else -> ErrorType.GENERIC
    }
}

@Composable
fun ErrorScreen(
    title: String = "Transfer Failed",
    message: String,
    errorType: ErrorType = mapErrorMessageToType(message),
    onRetry: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val icon: ImageVector = when (errorType) {
        ErrorType.NETWORK_DISCONNECTED -> Icons.Outlined.WifiOff
        ErrorType.STORAGE_FULL -> Icons.Outlined.FolderOff
        ErrorType.SECURITY_REJECTED -> Icons.Outlined.Security
        ErrorType.GENERIC -> Icons.Outlined.Warning
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.cancel))
            }
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
