package org.skepsun.kototoro.core.os

/**
 * Pure network-gating semantics used by [NetworkState], extracted so JVM unit tests can verify
 * the rules without Android stubs:
 *
 * - `hasNetwork`        — a default network exists at all (a VPN counts, even a broken one).
 * - `isValidated`       — the default network actually reached the internet
 *                         (NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED).
 * - `allowAutomaticTraffic` — automatic background/image traffic is permitted whenever a default
 *                         network exists. It used to require a validated network as well, but
 *                         "unvalidated" is also the normal state of a working Wi-Fi whose router
 *                         blocks the system connectivity probe: there the app looked offline, with
 *                         no covers and an "on device" filter over the library. A broken VPN now
 *                         costs failed requests instead, which is the cheaper mistake.
 */
internal object NetworkStateLogic {

    fun isValidated(
        hasInternetCapability: Boolean,
        hasValidatedCapability: Boolean,
    ): Boolean = hasInternetCapability && hasValidatedCapability

    fun allowAutomaticTraffic(
        hasNetwork: Boolean,
        offlineCheckDisabled: Boolean,
    ): Boolean = offlineCheckDisabled || hasNetwork
}
