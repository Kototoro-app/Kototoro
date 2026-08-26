package org.skepsun.kototoro.core.os

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM tests for the pure network-gating semantics that [NetworkState] applies to Android's
 * ConnectivityManager signal. Android's ConnectivityManager/NetworkCapabilities are stubbed in
 * plain JVM unit tests, so the combination logic lives in [NetworkStateLogic] and is verified
 * here; the Android callback binding itself is verified on device (P2).
 */
class NetworkStateLogicTest {

    @Test
    fun `an unvalidated vpn still has a network but blocks automatic traffic`() {
        // hasNetwork = true (VPN is the default network) but not yet validated / reachable.
        assertTrue(NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = true,
            isValidated = false,
            offlineCheckDisabled = false,
        ).not())
    }

    @Test
    fun `validated default network allows automatic traffic`() {
        assertTrue(NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = true,
            isValidated = true,
            offlineCheckDisabled = false,
        ))
    }

    @Test
    fun `no default network always blocks automatic traffic`() {
        assertFalse(NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = false,
            isValidated = false,
            offlineCheckDisabled = false,
        ))
        assertFalse(NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = false,
            isValidated = true,
            offlineCheckDisabled = false,
        ))
    }

    @Test
    fun `validation requires both internet and validated capabilities`() {
        assertFalse(NetworkStateLogic.isValidated(hasInternetCapability = true, hasValidatedCapability = false))
        assertFalse(NetworkStateLogic.isValidated(hasInternetCapability = false, hasValidatedCapability = true))
        assertTrue(NetworkStateLogic.isValidated(hasInternetCapability = true, hasValidatedCapability = true))
    }

    @Test
    fun `offline check disabled always allows automatic traffic`() {
        assertTrue(NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = false,
            isValidated = false,
            offlineCheckDisabled = true,
        ))
    }
}
