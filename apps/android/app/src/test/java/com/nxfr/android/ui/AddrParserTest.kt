package com.nxfr.android.ui

import org.junit.Assert.*
import org.junit.Test

class AddrParserTest {

    @Test
    fun `valid address with standard port`() {
        val result = parseAddr("192.168.1.5:17394")
        assertNotNull(result)
        assertEquals("192.168.1.5" to 17394, result)
    }

    @Test
    fun `valid address with high port`() {
        val result = parseAddr("10.0.0.1:65535")
        assertNotNull(result)
        assertEquals("10.0.0.1" to 65535, result)
    }

    @Test
    fun `valid address with port 1`() {
        val result = parseAddr("0.0.0.0:1")
        assertNotNull(result)
        assertEquals("0.0.0.0" to 1, result)
    }

    @Test
    fun `missing port returns null`() {
        assertNull(parseAddr("192.168.1.5"))
    }

    @Test
    fun `bad octet returns null`() {
        assertNull(parseAddr("256.1.1.1:17394"))
    }

    @Test
    fun `bad port 0 returns null`() {
        assertNull(parseAddr("192.168.1.1:0"))
    }

    @Test
    fun `bad port too high returns null`() {
        assertNull(parseAddr("192.168.1.1:65536"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseAddr(""))
    }

    @Test
    fun `whitespace trimmed`() {
        val result = parseAddr("  10.90.5.239:17394  ")
        assertNotNull(result)
        assertEquals("10.90.5.239" to 17394, result)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(parseAddr("not-an-ip"))
    }

    @Test
    fun `ipv6 returns null`() {
        assertNull(parseAddr("[::1]:17394"))
    }
}
