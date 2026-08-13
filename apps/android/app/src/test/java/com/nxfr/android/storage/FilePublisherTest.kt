package com.nxfr.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilePublisherTest {
    @org.junit.Before
    fun setUp() {
        tempFolder.create()
    }

    @get:Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun test_media_store_failure_fallback_to_file_api() {
        val inboxDir = tempFolder.newFolder("inbox")
        val inboxFile = File(inboxDir, "test_document.pdf")
        inboxFile.writeText("NXFR Secure Payload Data")

        val targetFallbackDir = File(tempFolder.newFolder("Downloads"), "NXFR").apply { mkdirs() }

        // Custom publisher simulates MediaStore failure (throws Exception)
        val resultPath = FilePublisher.publishToDownloads(
            context = null,
            inboxFile = inboxFile,
            targetFallbackDir = targetFallbackDir,
            mediaStorePublisher = { _ -> throw RuntimeException("Simulated MediaStore failure") }
        )

        // Verify fallback: file exists, contents match, and inbox file cleaned up
        val resultFile = File(resultPath)
        assertTrue("Published file must exist", resultFile.exists())
        assertEquals("File content must match inbox payload", "NXFR Secure Payload Data", resultFile.readText())
        assertFalse("Original inbox file must be deleted", inboxFile.exists())
    }
}
