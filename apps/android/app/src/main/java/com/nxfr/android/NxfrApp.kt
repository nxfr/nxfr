package com.nxfr.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NxfrApp : Application() {
    companion object {
        const val CHANNEL_SERVICE = "nxfr_service"
        const val CHANNEL_TRANSFERS = "nxfr_transfers"
        const val CHANNEL_OFFER = "nxfr_offer"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Purge any orphaned staging/send cache files from previous runs
        com.nxfr.android.storage.CacheCleaner.cleanAllStaleCacheSync(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection and discovery in the background"
            setShowBadge(false)
        }

        val transferChannel = NotificationChannel(
            CHANNEL_TRANSFERS,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live progress and status of file transfers"
            setShowBadge(true)
        }

        val offerChannel = NotificationChannel(
            CHANNEL_OFFER,
            "Incoming Transfers",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for incoming file transfer requests"
            setShowBadge(true)
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(transferChannel)
        nm.createNotificationChannel(offerChannel)
    }
}
