package org.skepsun.kototoro.desktop.compat

import eu.kanade.tachiyomi.network.PersistentCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * The pinned SDK writes domain-keyed Set-Cookie strings but truncates dotted domain keys on startup.
 * Recover missing persistent cookies from that same snapshot using public APIs; keep the SDK as owner.
 */
internal fun restoreDesktopCookies(store: PersistentCookieStore, snapshot: Map<String, *>) {
    val existing = store.getStoredCookies().mapTo(mutableSetOf()) { Triple(it.name, it.domain, it.path) }
    val now = System.currentTimeMillis()
    for ((domain, value) in snapshot) {
        val encoded = value as? Set<*> ?: continue
        val origin = runCatching { HttpUrl.Builder().scheme("https").host(domain).build() }.getOrNull() ?: continue
        val missing = encoded.mapNotNull { (it as? String)?.let { header -> Cookie.parse(origin, header) } }
            .filter { it.persistent && it.expiresAt > now && existing.add(Triple(it.name, it.domain, it.path)) }
        if (missing.isNotEmpty()) store.addAll(origin, missing)
    }
}
