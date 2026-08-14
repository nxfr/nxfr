package com.nxfr.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationFormatterTest {

    @Test
    fun testFormatTitleSending() {
        val title = TransferNotificationFormatter.formatTitle(
            isSending = true,
            fileName = "photo.jpg",
            peerName = "Pixel 8"
        )
        assertEquals("Sending photo.jpg → Pixel 8", title)
    }

    @Test
    fun testFormatTitleReceiving() {
        val title = TransferNotificationFormatter.formatTitle(
            isSending = false,
            fileName = "document.pdf",
            peerName = "MacBook Pro"
        )
        assertEquals("Receiving document.pdf ← MacBook Pro", title)
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("12.5 MB/s", TransferNotificationFormatter.formatSpeed(12.5 * 1024 * 1024))
        assertEquals("512 KB/s", TransferNotificationFormatter.formatSpeed(512.0 * 1024))
        assertEquals("500 B/s", TransferNotificationFormatter.formatSpeed(500.0))
        assertEquals("", TransferNotificationFormatter.formatSpeed(0.0))
    }

    @Test
    fun testFormatEta() {
        assertEquals("ETA 10s", TransferNotificationFormatter.formatEta(10 * 1024 * 1024, 1024 * 1024.0))
        assertEquals("ETA 2m 30s", TransferNotificationFormatter.formatEta(150 * 1024 * 1024, 1024 * 1024.0))
        assertEquals("ETA 1h 10m", TransferNotificationFormatter.formatEta(4200 * 1024 * 1024L, 1024 * 1024.0))
        assertEquals("ETA <1s", TransferNotificationFormatter.formatEta(0, 1024 * 1024.0))
    }

    @Test
    fun testFormatProgressTextSingleFile() {
        val text = TransferNotificationFormatter.formatProgressText(
            progressPct = 45,
            speedBps = 10.0 * 1024 * 1024,
            remainingBytes = 50 * 1024 * 1024,
            fileIndex = 1,
            totalFiles = 1
        )
        assertEquals("45% · 10.0 MB/s · ETA 5s", text)
    }

    @Test
    fun testFormatProgressTextMultiFile() {
        val text = TransferNotificationFormatter.formatProgressText(
            progressPct = 80,
            speedBps = 2.0 * 1024 * 1024,
            remainingBytes = 20 * 1024 * 1024,
            fileIndex = 3,
            totalFiles = 5
        )
        assertEquals("file 3/5 · 80% · 2.0 MB/s · ETA 10s", text)
    }

    @Test
    fun testFormatBytes() {
        assertEquals("1.5 GB", TransferNotificationFormatter.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("25.0 MB", TransferNotificationFormatter.formatBytes((25 * 1024 * 1024).toLong()))
        assertEquals("500.0 KB", TransferNotificationFormatter.formatBytes((500 * 1024).toLong()))
        assertEquals("256 B", TransferNotificationFormatter.formatBytes(256L))
    }
}
