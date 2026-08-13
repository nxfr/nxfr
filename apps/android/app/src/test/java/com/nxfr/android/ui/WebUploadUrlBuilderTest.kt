package com.nxfr.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUploadUrlBuilderTest {

    @Test
    fun testWebUploadUrl_isFragmentOnly_andHasNoQueryToken() {
        val primaryIp = "192.168.1.50"
        val uploadPort = 17396
        val uploadToken = "a1b2c3d4e5f6"

        val webUrl = "https://$primaryIp:$uploadPort/#t=$uploadToken"

        assertTrue("URL must contain fragment token #t=", webUrl.contains("#t=$uploadToken"))
        assertFalse("URL must NOT contain query token ?t=", webUrl.contains("?t="))
    }
}
