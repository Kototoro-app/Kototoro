package org.skepsun.kototoro.core.os

/**
 * Pure network-gating semantics used by [NetworkState], extracted so JVM unit tests can verify
 * the rules without Android stubs:
 *
 * - `hasNetwork`        — a default network exists at all (a VPN counts, even a broken one).
 * - `isValidated`       — the default network actually reached the internet
 *                         (NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED).
 * - `allowAutomaticTraffic` — automatic background/image traffic is permitted only when the
 *                         default network is validated. On an invalid VPN automatic traffic
 *                         stops, while user-initiated attempts keep running (they are gated
 *                         elsewhere, bypassing this check).
 */
internal object NetworkStateLogic {

    fun isValidated(
        hasInternetCapability: Boolean,
        hasValidatedCapability: Boolean,
    ): Boolean = hasInternetCapability && hasValidatedCapability

    fun allowAutomaticTraffic(
        hasNetwork: Boolean,
        isValidated: Boolean,
        offlineCheckDisabled: Boolean,
    ): Boolean = offlineCheckDisabled || (hasNetwork && isValidated)
}
