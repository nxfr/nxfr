package com.nxfr.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AddressParserTest {

    @Test
    fun testParse_plainIpv4_defaultsPort() {
        val result = AddressParser.parse("192.168.1.104")
        assertNotNull(result)
        assertEquals("192.168.1.104", result?.host)
        assertEquals(17394, result?.port)
        assertEquals("192.168.1.104:17394", result?.formatted)
    }

    @Test
    fun testParse_ipv4WithPort_parsesCorrectly() {
        val result = AddressParser.parse("192.168.1.104:8080")
        assertNotNull(result)
        assertEquals("192.168.1.104", result?.host)
        assertEquals(8080, result?.port)
        assertEquals("192.168.1.104:8080", result?.formatted)
    }

    @Test
    fun testParse_bracketedIpv6_defaultsPort() {
        val result = AddressParser.parse("[fe80::1]")
        assertNotNull(result)
        assertEquals("fe80::1", result?.host)
        assertEquals(17394, result?.port)
        assertEquals("[fe80::1]:17394", result?.formatted)
    }

    @Test
    fun testParse_bracketedIpv6WithPort_parsesCorrectly() {
        val result = AddressParser.parse("[2001:db8::1]:17394")
        assertNotNull(result)
        assertEquals("2001:db8::1", result?.host)
        assertEquals(17394, result?.port)
        assertEquals("[2001:db8::1]:17394", result?.formatted)
    }

    @Test
    fun testParse_bareIpv6_defaultsPort() {
        val result = AddressParser.parse("fe80::1")
        assertNotNull(result)
        assertEquals("fe80::1", result?.host)
        assertEquals(17394, result?.port)
        assertEquals("[fe80::1]:17394", result?.formatted)
    }

    @Test
    fun testParse_hostname_defaultsPort() {
        val result = AddressParser.parse("laptop.local")
        assertNotNull(result)
        assertEquals("laptop.local", result?.host)
        assertEquals(17394, result?.port)
        assertEquals("laptop.local:17394", result?.formatted)
    }

    @Test
    fun testParse_hostnameWithPort_parsesCorrectly() {
        val result = AddressParser.parse("my-pc.lan:9000")
        assertNotNull(result)
        assertEquals("my-pc.lan", result?.host)
        assertEquals(9000, result?.port)
        assertEquals("my-pc.lan:9000", result?.formatted)
    }

    @Test
    fun testParse_urlPrefixes_strippedGracefully() {
        val r1 = AddressParser.parse("http://192.168.1.50:17394/")
        assertNotNull(r1)
        assertEquals("192.168.1.50", r1?.host)
        assertEquals(17394, r1?.port)

        val r2 = AddressParser.parse("https://10.0.0.1:17396/#t=123")
        assertNotNull(r2)
        assertEquals("10.0.0.1", r2?.host)
        assertEquals(17396, r2?.port)

        val r3 = AddressParser.parse("nxfr://192.168.1.200:17394")
        assertNotNull(r3)
        assertEquals("192.168.1.200", r3?.host)
        assertEquals(17394, r3?.port)
    }

    @Test
    fun testParse_whitespaceTrimmed() {
        val result = AddressParser.parse("   192.168.1.5:17394   ")
        assertNotNull(result)
        assertEquals("192.168.1.5", result?.host)
        assertEquals(17394, result?.port)
    }

    @Test
    fun testParse_invalidInputs_returnsNull() {
        assertNull(AddressParser.parse(""))
        assertNull(AddressParser.parse("   "))
        assertNull(AddressParser.parse(null))
        assertNull(AddressParser.parse("192.168.1.1:99999")) // Port out of range
        assertNull(AddressParser.parse("192.168.1.1:0")) // Port 0 invalid
        assertNull(AddressParser.parse("192.168.1.1:abc")) // Non-numeric port
        assertNull(AddressParser.parse("[fe80::1:17394")) // Unmatched bracket
        assertNull(AddressParser.parse(":::invalid:colon:count:::"))
        assertNull(AddressParser.parse("not an ip with spaces:17394"))
    }
}
