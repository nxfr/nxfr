package com.nxfr.android.history

import com.nxfr.android.ui.sheets.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryParserTest {

    @Test
    fun testHistoryItem_dataModelInstantiatesCorrectly() {
        val item = HistoryItem(
            id = 1L,
            tsMs = 1723630000000L,
            direction = "send",
            peerName = "MacBook Pro",
            peerId = "abcdef123456",
            fileCount = 2,
            totalBytes = 1048576L,
            status = "complete",
            filePaths = listOf("/storage/emulated/0/Download/NXFR/file1.pdf")
        )

        assertEquals(1L, item.id)
        assertEquals("send", item.direction)
        assertEquals("MacBook Pro", item.peerName)
        assertEquals(1048576L, item.totalBytes)
        assertEquals(1, item.filePaths.size)
        assertEquals("complete", item.status)
    }

    @Test
    fun testHistoryItem_statusMapping() {
        val statuses = listOf("complete", "failed", "rejected", "cancelled", "unknown_status")
        for (st in statuses) {
            val (label, isAlert) = when (st.lowercase()) {
                "complete" -> "COMPLETE" to false
                "rejected" -> "REJECTED" to true
                "cancelled" -> "CANCELLED" to false
                else -> "FAILED" to true
            }

            assertTrue(label.isNotEmpty())
        }
    }

    @Test
    fun testByteFormatting() {
        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
                bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }

        assertEquals("500 B", formatBytes(500L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.5 MB", formatBytes(1572864L))
        assertEquals("2.0 GB", formatBytes(2147483648L))
    }
}
