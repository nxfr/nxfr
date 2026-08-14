package com.nxfr.android.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsVCardTest {

    @Test
    fun testSanitizeFilename() {
        val rawName = "Dr. Jane Doe / Chief (HQ) & Co. <Mobile>"
        val sanitized = rawName.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        assertEquals("Dr. Jane Doe _ Chief _HQ_ _ Co. _Mobile_", sanitized)
    }

    @Test
    fun testVCardHeaderValidation_validHeader_returnsTrue() {
        val validVcard = """
            BEGIN:VCARD
            VERSION:3.0
            N:Doe;John;;;
            FN:John Doe
            TEL;TYPE=CELL:+1234567890
            END:VCARD
        """.trimIndent()

        val hasHeader = validVcard.lines().any { it.contains("BEGIN:VCARD", ignoreCase = true) }
        assertTrue(hasHeader)
    }

    @Test
    fun testVCardHeaderValidation_missingHeader_returnsFalse() {
        val invalidVcard = """
            Some random text without vcard header
            TEL: 123456
        """.trimIndent()

        val hasHeader = invalidVcard.lines().any { it.contains("BEGIN:VCARD", ignoreCase = true) }
        assertFalse(hasHeader)
    }
}
