package com.nxfr.android.transfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nxfr.android.MainActivity
import com.nxfr.android.NxfrApp

class TransferNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    fun showIncomingOfferNotification(offerId: Int, fileName: String, fileSize: Long) {
        val acceptIntent = Intent(context, MainActivity::class.java) // Replace with accept receiver
        val rejectIntent = Intent(context, MainActivity::class.java) // Replace with reject receiver
        
        val acceptPendingIntent = PendingIntent.getActivity(context, 0, acceptIntent, PendingIntent.FLAG_IMMUTABLE)
        val rejectPendingIntent = PendingIntent.getActivity(context, 1, rejectIntent, PendingIntent.FLAG_IMMUTABLE)
        
        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_OFFER)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Incoming File Offer")
            .setContentText("Receive $fileName ($fileSize bytes)?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_add, "Accept", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPendingIntent)
            .build()
            
        notificationManager.notify(offerId, notification)
    }
    
    fun showTransferProgressNotification(transferId: Int, fileName: String, progress: Int, max: Int) {
        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Transferring $fileName")
            .setProgress(max, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(transferId, notification)
    }
    
    fun showTransferCompleteNotification(transferId: Int, fileName: String) {
        val notification = NotificationCompat.Builder(context, NxfrApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Transfer Complete")
            .setContentText("Successfully transferred $fileName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            
        notificationManager.notify(transferId, notification)
    }
}
