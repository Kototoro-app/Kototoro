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
    fun `an unvalidated network still allows automatic traffic`() {
        // A working Wi-Fi whose router blocks the connectivity probe is never validated.
        assertTrue(NetworkStateLogic.allowAutomaticTraffic(hasNetwork = true, offlineCheckDisabled = false))
    }

    @Test
    fun `no default network blocks automatic traffic`() {
        assertFalse(NetworkStateLogic.allowAutomaticTraffic(hasNetwork = false, offlineCheckDisabled = false))
    }

    @Test
    fun `validation requires both internet and validated capabilities`() {
        assertFalse(NetworkStateLogic.isValidated(hasInternetCapability = true, hasValidatedCapability = false))
        assertFalse(NetworkStateLogic.isValidated(hasInternetCapability = false, hasValidatedCapability = true))
        assertTrue(NetworkStateLogic.isValidated(hasInternetCapability = true, hasValidatedCapability = true))
    }

    @Test
    fun `offline check disabled always allows automatic traffic`() {
        assertTrue(NetworkStateLogic.allowAutomaticTraffic(hasNetwork = false, offlineCheckDisabled = true))
    }
}
