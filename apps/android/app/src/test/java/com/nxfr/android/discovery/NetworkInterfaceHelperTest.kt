package com.nxfr.android.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfaceHelperTest {

    @Test
    fun testCgnatDetection() {
        // CGNAT block is 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        assertTrue(NetworkInterfaceHelper.isCgnat("100.64.0.1"))
        assertTrue(NetworkInterfaceHelper.isCgnat("100.100.50.20"))
        assertTrue(NetworkInterfaceHelper.isCgnat("100.127.255.254"))

        // Outside CGNAT
        assertFalse(NetworkInterfaceHelper.isCgnat("100.63.255.255"))
        assertFalse(NetworkInterfaceHelper.isCgnat("100.128.0.1"))
        assertFalse(NetworkInterfaceHelper.isCgnat("192.168.1.100"))
        assertFalse(NetworkInterfaceHelper.isCgnat("10.0.0.1"))
        assertFalse(NetworkInterfaceHelper.isCgnat("172.20.10.1"))
        assertFalse(NetworkInterfaceHelper.isCgnat("invalid-ip"))
    }

    @Test
    fun testCellularInterfaceDetection() {
        assertTrue(NetworkInterfaceHelper.isCellularInterface("rmnet0"))
        assertTrue(NetworkInterfaceHelper.isCellularInterface("rmnet_data0"))
        assertTrue(NetworkInterfaceHelper.isCellularInterface("pdp0"))
        assertTrue(NetworkInterfaceHelper.isCellularInterface("ccmni0"))
        assertTrue(NetworkInterfaceHelper.isCellularInterface("wwan0"))
        assertTrue(NetworkInterfaceHelper.isCellularInterface("dummy0"))

        assertFalse(NetworkInterfaceHelper.isCellularInterface("wlan0"))
        assertFalse(NetworkInterfaceHelper.isCellularInterface("ap0"))
        assertFalse(NetworkInterfaceHelper.isCellularInterface("p2p-wlan0-0"))
        assertFalse(NetworkInterfaceHelper.isCellularInterface("eth0"))
        assertFalse(NetworkInterfaceHelper.isCellularInterface("swlan0"))
    }

    @Test
    fun testPreferredInterfaceDetection() {
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("wlan0"))
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("ap0"))
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("swlan0"))
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("p2p-wlan0-0"))
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("eth0"))
        assertTrue(NetworkInterfaceHelper.isPreferredInterface("en0"))

        assertFalse(NetworkInterfaceHelper.isPreferredInterface("rmnet0"))
        assertFalse(NetworkInterfaceHelper.isPreferredInterface("pdp0"))
    }
}
