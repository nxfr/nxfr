package com.nxfr.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NxfrApp : Application() {
    companion object {
        const val CHANNEL_TRANSFER = "nxfr_transfer"
        const val CHANNEL_OFFER = "nxfr_offer"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val transferChannel = NotificationChannel(
            CHANNEL_TRANSFER,
            "Transfer Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows file transfer progress"
        }

        val offerChannel = NotificationChannel(
            CHANNEL_OFFER,
            "Incoming Transfers",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming transfer offers"
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(transferChannel)
        nm.createNotificationChannel(offerChannel)
    }
}
