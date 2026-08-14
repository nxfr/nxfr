package com.nxfr.android.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.nxfr.android.MainActivity
import com.nxfr.android.NxfrApp
import com.nxfr.android.service.NxfrService

class TransferNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val THROTTLE_INTERVAL_MS = 250L // ≤ 4 Hz
        private const val NOTIFICATION_GROUP = "com.nxfr.android.TRANSFERS_GROUP"
        private const val SUMMARY_NOTIFICATION_ID = 1000
        private var lastUpdateTimeMs = 0L
        private var lastBytesTransferred = 0L
        private var lastSpeedBps = 0.0
    }

    fun showIncomingOfferNotification(offerId: Int, fileName: String, fileSize: Long, peerName: String = "Nearby device") {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            offerId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_OFFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Incoming transfer offer")
            .setContentText("$peerName wants to send $fileName (${TransferNotificationFormatter.formatBytes(fileSize)})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(offerId, notification)
    }

    fun showTransferProgressNotification(
        transferId: Int,
        isSending: Boolean,
        fileName: String,
        peerName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        fileIndex: Int = 1,
        totalFiles: Int = 1
    ) {
        val now = System.currentTimeMillis()
        val dt = now - lastUpdateTimeMs
        val isTerminal = totalBytes > 0 && bytesTransferred >= totalBytes

        // Throttle updates to ≤ 4 Hz (250ms) unless it's the start or finish
        if (dt < THROTTLE_INTERVAL_MS && !isTerminal && lastUpdateTimeMs != 0L) {
            return
        }

        // Speed calculation with smoothing
        val dBytes = bytesTransferred - lastBytesTransferred
        if (dt > 0 && dBytes >= 0) {
            val instantSpeed = (dBytes.toDouble() / dt.toDouble()) * 1000.0
            lastSpeedBps = if (lastSpeedBps <= 0.0) instantSpeed else (0.7 * lastSpeedBps + 0.3 * instantSpeed)
        }
        lastUpdateTimeMs = now
        lastBytesTransferred = bytesTransferred

        val progressPct = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        val remainingBytes = if (totalBytes > bytesTransferred) totalBytes - bytesTransferred else 0L

        val title = TransferNotificationFormatter.formatTitle(isSending, fileName, peerName)
        val text = TransferNotificationFormatter.formatProgressText(
            progressPct = progressPct,
            speedBps = lastSpeedBps,
            remainingBytes = remainingBytes,
            fileIndex = fileIndex,
            totalFiles = totalFiles
        )

        // Tap action -> Open TransferScreen / MainActivity
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            transferId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Cancel action -> Send cancel intent to NxfrService
        val cancelIntent = Intent(context, NxfrService::class.java).apply {
            action = NxfrService.ACTION_CANCEL_TRANSFER
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            transferId + 10000,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_TRANSFERS)
            .setSmallIcon(if (isSending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progressPct, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .setGroup(NOTIFICATION_GROUP)
            .build()

        notificationManager.notify(transferId, notification)
    }

    fun showTransferCompleteNotification(
        transferId: Int,
        fileName: String,
        fileSize: Long = 0L,
        publishedPath: String = "",
        isSending: Boolean = false,
        peerName: String = ""
    ) {
        // Reset speed tracking
        lastUpdateTimeMs = 0L
        lastBytesTransferred = 0L
        lastSpeedBps = 0.0

        val openIntent = if (isSending) {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        } else {
            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            transferId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sizeStr = TransferNotificationFormatter.formatBytes(fileSize)
        val subtitle = if (isSending) {
            "$sizeStr • Sent to ${peerName.ifBlank { "peer" }}"
        } else {
            val locStr = if (publishedPath.isBlank() || publishedPath.contains("Download")) "Downloads/NXFR" else publishedPath
            "$sizeStr • Saved to $locStr"
        }

        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Transfer complete ✓")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(4000L) // Auto-dismiss after 4s
            .setContentIntent(pendingIntent)
            .setGroup(NOTIFICATION_GROUP)
            .build()

        notificationManager.notify(transferId, notification)

        // Delayed cancellation guarantee
        mainHandler.postDelayed({
            try { notificationManager.cancel(transferId) } catch (_: Throwable) {}
        }, 4000L)
    }

    fun showTransferFailedNotification(
        transferId: Int,
        title: String = "Transfer failed",
        message: String = "Transfer could not be completed"
    ) {
        lastUpdateTimeMs = 0L
        lastBytesTransferred = 0L
        lastSpeedBps = 0.0

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            transferId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(NOTIFICATION_GROUP)
            .build()

        notificationManager.notify(transferId, notification)
    }

    fun cancelNotification(transferId: Int) {
        notificationManager.cancel(transferId)
    }
}
