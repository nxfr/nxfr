package com.nxfr.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NxfrQrTicketParserTest {

    @Test
    fun testParse_validConnectTicket_returnsConnectTicket() {
        val qr = "nxfr://connect?did=abc1234567890def&addr=192.168.1.50:17394"
        val result = NxfrQrTicketParser.parse(qr)
        assertTrue(result is QrScanResult.ConnectTicket)
        val ticket = result as QrScanResult.ConnectTicket
        assertEquals("abc1234567890def", ticket.deviceId)
        assertEquals("192.168.1.50:17394", ticket.addr)
    }

    @Test
    fun testParse_webUploadLink_returnsWebUploadLink() {
        val qr = "https://192.168.1.50:17396/#t=0123456789abcdef0123456789abcdef"
        val result = NxfrQrTicketParser.parse(qr)
        assertEquals(QrScanResult.WebUploadLink, result)
    }

    @Test
    fun testParse_missingAddr_returnsInvalid() {
        val qr = "nxfr://connect?did=abc1234567890def"
        val result = NxfrQrTicketParser.parse(qr)
        assertEquals(QrScanResult.Invalid, result)
    }

    @Test
    fun testParse_garbageString_returnsInvalid() {
        val qr = "random_text_or_url_without_nxfr"
        val result = NxfrQrTicketParser.parse(qr)
        assertEquals(QrScanResult.Invalid, result)
    }
}
