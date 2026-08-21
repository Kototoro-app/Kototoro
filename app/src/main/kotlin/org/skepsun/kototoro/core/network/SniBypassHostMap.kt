package org.skepsun.kototoro.core.network

import java.util.concurrent.ConcurrentHashMap

internal object SniBypassHostMap {
    private val map = ConcurrentHashMap<String, String>()

    fun register(safeHost: String, originalHost: String) {
        if (safeHost.isNotBlank() && originalHost.isNotBlank()) {
            map[safeHost] = originalHost
        }
    }

    fun resolve(host: String): String? = map[host]
}
