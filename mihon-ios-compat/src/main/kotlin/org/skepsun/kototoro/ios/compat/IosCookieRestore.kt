package org.skepsun.kototoro.ios.compat

import eu.kanade.tachiyomi.network.PersistentCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * The pinned Mobile API writes domain-keyed Set-Cookie strings but, on startup, cuts each key at its last dot
 * (`n.novelia.cc` is looked up as `n.novelia`), so every saved cookie of a dotted domain — a parser's login, a
 * Cloudflare clearance — is forgotten on the next launch. Recover the persistent ones from that same snapshot with
 * public APIs and keep the store as owner; the Windows adapter does the same (`restoreDesktopCookies`).
 */
internal fun restoreIosCookies(store: PersistentCookieStore, snapshot: Map<String, *>) {
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
