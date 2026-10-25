package org.skepsun.kototoro.desktop.app

import java.net.NetworkInterface
import java.util.Collections

/**
 * Android's `NetworkType.UNMETERED` for the suggestions worker (`KEY_SUGGESTIONS_WIFI_ONLY`): WorkManager refuses to
 * run the job while the only network is a metered mobile link, and that is the whole meaning of the switch.
 *
 * The JVM has no metered flag, so the Windows host reads the adapters themselves: a link counts as unmetered when an
 * adapter is up, is not the loopback one, and does not name itself as mobile broadband. [DesktopNetworkInterface] is
 * deliberately separate from [NetworkInterface] so the rule stays testable without this machine's hardware.
 */
internal data class DesktopNetworkInterface(val name: String, val displayName: String?, val isUp: Boolean,
                                            val isLoopback: Boolean) {
    /** Windows names WWAN adapters after the modem; Android's UNMETERED excludes exactly that transport. */
    val isMobile: Boolean
        get() = MOBILE_MARKERS.any { marker -> name.contains(marker) || displayName?.contains(marker) == true }

    private companion object {
        val MOBILE_MARKERS = listOf("wwan", "cellular", "mobile", "rmnet", "modem", "lte", "4g", "5g")
    }
}

/** True while a link Android would call unmetered is up. A connected Ethernet cable counts, as it does on Android. */
internal fun hasUnmeteredNetwork(interfaces: List<DesktopNetworkInterface>): Boolean =
    interfaces.any { it.isUp && !it.isLoopback && !it.isMobile }

/** Reads the machine's adapters; a failure reports no adapter, which only asks the worker to wait. */
internal fun desktopNetworkInterfaces(): List<DesktopNetworkInterface> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces()).map { network ->
        DesktopNetworkInterface(
            name = network.name.orEmpty().lowercase(),
            displayName = network.displayName?.lowercase(),
            isUp = network.isUp,
            isLoopback = network.isLoopback,
        )
    }
}.getOrDefault(emptyList())
