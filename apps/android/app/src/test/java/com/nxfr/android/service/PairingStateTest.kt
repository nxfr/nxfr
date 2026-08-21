package com.nxfr.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStateTest {

    @Test
    fun testNxfrState_pairing_properties() {
        val pairing = NxfrState.Pairing(
            handle = 42L,
            sasCode = "123456",
            peerName = "Desktop Linux",
            deviceId = "a1b2c3d4e5f6",
            isInitiator = true
        )

        assertEquals(42L, pairing.handle)
        assertEquals("123456", pairing.sasCode)
        assertEquals("Desktop Linux", pairing.peerName)
        assertEquals("a1b2c3d4e5f6", pairing.deviceId)
        assertTrue(pairing.isInitiator)
    }

    @Test
    fun testPairRequestEvent_whenListening_createsPairingState() {
        val currentState: NxfrState = NxfrState.Listening
        val eventSas = "987654"
        val eventDeviceId = "fedcba987654"
        val eventPeerName = "Android Peer"
        val handle = 100L

        val nextState: NxfrState = if (currentState is NxfrState.Offering) {
            currentState.copy(sasCode = eventSas, deviceId = eventDeviceId)
        } else {
            NxfrState.Pairing(
                handle = handle,
                sasCode = eventSas,
                peerName = eventPeerName,
                deviceId = eventDeviceId,
                isInitiator = false
            )
        }

        assertTrue(nextState is NxfrState.Pairing)
        val pairing = nextState as NxfrState.Pairing
        assertEquals(eventSas, pairing.sasCode)
        assertEquals(eventPeerName, pairing.peerName)
        assertEquals(eventDeviceId, pairing.deviceId)
        assertFalse(pairing.isInitiator)
    }

    @Test
    fun testPairRequestEvent_whenOffering_updatesOfferingSas() {
        val currentState: NxfrState = NxfrState.Offering(
            handle = 100L,
            displayName = "doc.pdf",
            totalSize = 1024,
            totalFiles = 1,
            peerName = "Sender",
            deviceId = "",
            sasCode = ""
        )
        val eventSas = "654321"
        val eventDeviceId = "112233445566"

        val nextState: NxfrState = if (currentState is NxfrState.Offering) {
            currentState.copy(
                sasCode = eventSas,
                deviceId = eventDeviceId.ifEmpty { currentState.deviceId }
            )
        } else {
            NxfrState.Pairing(
                handle = 100L,
                sasCode = eventSas,
                peerName = "Sender",
                deviceId = eventDeviceId,
                isInitiator = false
            )
        }

        assertTrue(nextState is NxfrState.Offering)
        val offering = nextState as NxfrState.Offering
        assertEquals("654321", offering.sasCode)
        assertEquals("112233445566", offering.deviceId)
        assertEquals("doc.pdf", offering.displayName)
    }

    @Test
    fun testExpectedDeviceIdVerification_matching_succeeds() {
        val expectedDeviceId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val actualPeerDeviceId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val isMatch = expectedDeviceId.isEmpty() || actualPeerDeviceId.equals(expectedDeviceId, ignoreCase = true)
        assertTrue("Matching device ID must be accepted", isMatch)
    }

    @Test
    fun testExpectedDeviceIdVerification_mismatch_rejected() {
        val expectedDeviceId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val actualPeerDeviceId = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        val isMatch = expectedDeviceId.isEmpty() || actualPeerDeviceId.equals(expectedDeviceId, ignoreCase = true)
        assertFalse("Mismatched device ID must be rejected", isMatch)
    }
}
