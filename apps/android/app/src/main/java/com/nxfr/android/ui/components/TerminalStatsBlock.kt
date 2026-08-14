package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.deckColors
import java.util.Locale

@Composable
fun TerminalStatsBlock(
    isSending: Boolean,
    fileName: String,
    progressBytes: Long,
    totalBytes: Long,
    speedMbps: Double,
    peakSpeedMbps: Double,
    etaSec: Double,
    socketAddr: String = "127.0.0.1:17394",
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val percent = if (totalBytes > 0) (progressBytes.toDouble() / totalBytes * 100.0) else 0.0

    val streamType = if (isSending) "[TX_STREAM]" else "[RX_STREAM]"
    val formattedSpeed = String.format(Locale.US, "%.1f MB/s", speedMbps)
    val formattedPeak = String.format(Locale.US, "%.1f MB/s", peakSpeedMbps)
    val formattedEta = String.format(Locale.US, "%02d:%04.1fs", (etaSec / 60).toInt(), etaSec % 60)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(deck.surfaceContainer, RoundedCornerShape(4.dp))
            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Stream Header
        Text(
            text = "$streamType $fileName",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = deck.signalBeam
        )

        // Telemetry Rows
        TelemetryRow(label = "PAYLOAD", value = "${formatBytes(progressBytes)} / ${formatBytes(totalBytes)} (${String.format(Locale.US, "%.1f", percent)}%)")
        TelemetryRow(label = "SPEED", value = "$formattedSpeed [PEAK: $formattedPeak]")
        TelemetryRow(label = "ETA", value = formattedEta)
        TelemetryRow(label = "SOCKET", value = socketAddr)
        TelemetryRow(label = "INTEGRITY", value = "SHA-256 VERIFIED STREAM")
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    val deck = MaterialTheme.deckColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = deck.textSecondary
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = deck.textPrimary
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
