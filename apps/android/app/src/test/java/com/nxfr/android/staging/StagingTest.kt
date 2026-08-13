package com.nxfr.android.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StagingTest {

    @Before
    fun setUp() {
        StagingRepository.clear()
    }

    @Test
    fun testStaging_calculateTotalSizeAndFiles() {
        val item1 = StagedItem(
            id = "1",
            type = StagedType.FILE,
            displayName = "doc.pdf",
            sizeBytes = 1000L
        )
        val item2 = StagedItem(
            id = "2",
            type = StagedType.FOLDER,
            displayName = "photos",
            sizeBytes = 5000L,
            isFolder = true,
            fileCount = 5
        )

        val fakeContext = object : android.content.ContextWrapper(null) {}
        StagingRepository.addItem(fakeContext, item1)
        StagingRepository.addItem(fakeContext, item2)

        assertEquals(6000L, StagingRepository.calculateTotalSize())
        assertEquals(6, StagingRepository.calculateTotalFiles())
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", StagingRepository.formatBytes(0))
        assertEquals("1.0 KB", StagingRepository.formatBytes(1024))
        assertEquals("1.0 MB", StagingRepository.formatBytes(1024 * 1024))
        assertEquals("5.0 MB", StagingRepository.formatBytes(5 * 1024 * 1024))
    }
}
