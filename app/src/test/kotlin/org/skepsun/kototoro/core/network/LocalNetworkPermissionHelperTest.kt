package org.skepsun.kototoro.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalNetworkPermissionHelperTest {

    @Test
    fun `isRequired only on Android 17 and above`() {
        assertFalse(LocalNetworkPermissionHelper.isRequired(sdkInt = 35))
        assertFalse(LocalNetworkPermissionHelper.isRequired(sdkInt = 36))
        assertTrue(LocalNetworkPermissionHelper.isRequired(sdkInt = 37))
        assertTrue(LocalNetworkPermissionHelper.isRequired(sdkInt = 38))
    }

    @Test
    fun `detects RFC1918 and local IPv4 addresses`() {
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("192.168.10.100"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("192.168.10.100:7890"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("http://192.168.10.100:7890/dav"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("10.0.0.1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("172.16.0.1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("172.31.255.254"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("169.254.1.1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("100.64.0.1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("100.127.255.254"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("239.255.255.250"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("255.255.255.255"))
    }

    @Test
    fun `excludes loopback and public IPv4 addresses`() {
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("127.0.0.1"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("127.0.0.1:7890"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("localhost"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("http://localhost:8080"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("8.8.8.8"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("1.1.1.1"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("172.15.255.255"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("172.32.0.1"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("100.63.255.255"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("100.128.0.1"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("proxy.example.com"))
    }

    @Test
    fun `detects dot local and local IPv6 addresses`() {
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("nas.local"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("https://nas.local:5006/dav"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("fe80::1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("[fe80::1%wlan0]:7890"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("fd00::1234"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("fc00::1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("ff02::1"))
        assertTrue(LocalNetworkPermissionHelper.isLocalNetworkHost("::ffff:192.168.10.100"))

        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("::1"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("[::1]:7890"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("2001:4860:4860::8888"))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `handles blank and malformed inputs safely`() {
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost(null))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost(""))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("   "))
        assertFalse(LocalNetworkPermissionHelper.isLocalNetworkHost("999.168.1.1"))
        assertEquals("192.168.10.100", LocalNetworkPermissionHelper.extractHost("http://user:pass@192.168.10.100:7890/path?x=1"))
    }
}
