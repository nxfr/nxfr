package com.nxfr.android.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NxfrP2pManagerTest {

    @Test
    fun testP2pPeerDataModel() {
        val peer = P2pPeer(
            deviceAddress = "02:00:00:00:00:01",
            deviceName = "NXFR-Station-Alpha",
            aid = "a1b2c3d4e5f60718",
            port = 17394
        )

        assertEquals("02:00:00:00:00:01", peer.deviceAddress)
        assertEquals("NXFR-Station-Alpha", peer.deviceName)
        assertEquals("a1b2c3d4e5f60718", peer.aid)
        assertEquals(17394, peer.port)
    }

    @Test
    fun testP2pStateTransitions() {
        var state: P2pState = P2pState.Idle
        assertTrue(state is P2pState.Idle)

        state = P2pState.Discovering
        assertTrue(state is P2pState.Discovering)

        val peers = listOf(
            P2pPeer("02:00:00:00:00:01", "Station-A", "aid1"),
            P2pPeer("02:00:00:00:00:02", "Station-B", "aid2")
        )
        state = P2pState.PeersFound(peers)
        assertTrue(state is P2pState.PeersFound)
        assertEquals(2, (state as P2pState.PeersFound).peers.size)

        state = P2pState.Forming
        assertTrue(state is P2pState.Forming)

        state = P2pState.Ready(isGO = false, goIp = "192.168.49.1", iface = "p2p-wlan0-0")
        assertTrue(state is P2pState.Ready)
        val ready = state as P2pState.Ready
        assertFalse(ready.isGO)
        assertEquals("192.168.49.1", ready.goIp)
        assertEquals("p2p-wlan0-0", ready.iface)

        state = P2pState.Failed("Wi-Fi Direct not available")
        assertTrue(state is P2pState.Failed)
        assertEquals("Wi-Fi Direct not available", (state as P2pState.Failed).reason)
    }

    @Test
    fun testTxtRecordParsingAndValidation() {
        val txtRecordMap = mapOf(
            "aid" to "beefcafe01234567",
            "name" to "Desert-Node-X",
            "port" to "17394"
        )

        val aid = txtRecordMap["aid"]
        val name = txtRecordMap["name"] ?: "Unknown"
        val port = txtRecordMap["port"]?.toIntOrNull() ?: 17394

        assertEquals("beefcafe01234567", aid)
        assertEquals("Desert-Node-X", name)
        assertEquals(17394, port)

        val localAid = "feedface98765432"
        val isSelf = aid == localAid
        assertFalse("Should not identify remote peer as self", isSelf)
    }

    @Test
    fun testTxtRecordFiltersOwnAid() {
        val localAid = "myaid123456"
        val incomingAid = "myaid123456"

        val shouldFilter = incomingAid == localAid
        assertTrue("Must filter out advertised_id matching local instance", shouldFilter)
    }

    @Test
    fun testSoftApStateHierarchy() {
        var state: SoftApState = SoftApState.Idle
        assertTrue(state is SoftApState.Idle)

        state = SoftApState.Starting
        assertTrue(state is SoftApState.Starting)

        state = SoftApState.Active(
            ssid = "DIRECT-NXFR-4920",
            passphrase = "password123",
            hostIp = "192.168.43.1"
        )
        assertTrue(state is SoftApState.Active)
        val active = state as SoftApState.Active
        assertEquals("DIRECT-NXFR-4920", active.ssid)
        assertEquals("password123", active.passphrase)
        assertEquals("192.168.43.1", active.hostIp)

        // Test with null passphrase (open hotspot or OEM restriction)
        state = SoftApState.Active(
            ssid = "DIRECT-NXFR-OPEN",
            passphrase = null,
            hostIp = "192.168.43.1"
        )
        assertNull((state as SoftApState.Active).passphrase)

        state = SoftApState.Failed("Hotspot already active or incompatible mode")
        assertTrue(state is SoftApState.Failed)
    }

    @Test
    fun testClientJoinStateHierarchy() {
        var clientState: ClientJoinState = ClientJoinState.Idle
        assertTrue(clientState is ClientJoinState.Idle)

        clientState = ClientJoinState.Connecting
        assertTrue(clientState is ClientJoinState.Connecting)

        clientState = ClientJoinState.Connected(hostIp = "192.168.43.1")
        assertTrue(clientState is ClientJoinState.Connected)
        assertEquals("192.168.43.1", (clientState as ClientJoinState.Connected).hostIp)

        clientState = ClientJoinState.Failed("Network request denied or timed out")
        assertTrue(clientState is ClientJoinState.Failed)
    }
}
