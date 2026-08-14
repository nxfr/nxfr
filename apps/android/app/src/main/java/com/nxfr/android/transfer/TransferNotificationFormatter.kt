package com.nxfr.android.transfer

import java.util.Locale

object TransferNotificationFormatter {

    fun formatTitle(isSending: Boolean, fileName: String, peerName: String): String {
        val cleanPeer = peerName.ifBlank { "Peer" }
        val cleanFile = fileName.ifBlank { "file" }
        return if (isSending) {
            "Sending $cleanFile → $cleanPeer"
        } else {
            "Receiving $cleanFile ← $cleanPeer"
        }
    }

    fun formatProgressText(
        progressPct: Int,
        speedBps: Double,
        remainingBytes: Long,
        fileIndex: Int = 1,
        totalFiles: Int = 1
    ): String {
        val clampedPct = progressPct.coerceIn(0, 100)
        val speedStr = formatSpeed(speedBps)
        val etaStr = formatEta(remainingBytes, speedBps)

        val parts = mutableListOf<String>()
        if (totalFiles > 1) {
            parts.add("file $fileIndex/$totalFiles")
        }
        parts.add("$clampedPct%")
        if (speedStr.isNotEmpty()) {
            parts.add(speedStr)
        }
        if (etaStr.isNotEmpty()) {
            parts.add(etaStr)
        }

        return parts.joinToString(" · ")
    }

    fun formatSpeed(speedBps: Double): String {
        if (speedBps <= 0.0) return ""
        return when {
            speedBps >= 1_048_576.0 -> String.format(Locale.US, "%.1f MB/s", speedBps / 1_048_576.0)
            speedBps >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", speedBps / 1024.0)
            else -> "${speedBps.toInt()} B/s"
        }
    }

    fun formatEta(remainingBytes: Long, speedBps: Double): String {
        if (speedBps <= 0.0 || remainingBytes < 0L) return ""
        val etaSec = (remainingBytes / speedBps).toLong()
        return when {
            etaSec <= 0L -> "ETA <1s"
            etaSec < 60L -> "ETA ${etaSec}s"
            etaSec < 3600L -> "ETA ${etaSec / 60}m ${etaSec % 60}s"
            else -> "ETA ${etaSec / 3600}h ${(etaSec % 3600) / 60}m"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
