package org.skepsun.kototoro.mihon.compat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiXSourceAdapter
import org.skepsun.kototoro.parsers.model.ContentSource

/** Immutable source authority attached to a single Mihon HTTP request. */
data class SourceRequestContext(
    val source: ContentSource,
    val allowedBrowserOrigins: Set<String> = emptySet(),
) {
    fun allowsBrowserRequest(url: String): Boolean = url.toHttpsOrigin() in allowedBrowserOrigins

    companion object {
        fun from(source: ContentSource): SourceRequestContext {
            // Any Tachiyomi-ABI ecosystem source (Mihon today, Tsundoku later) contributes its
            // base URL through the shared adapter; no more hard cast to MihonMangaSource (§6.2).
            val baseOrigin = (source as? TachiyomiXSourceAdapter)
                ?.baseUrlOrNull
                ?.toHttpsOrigin()
            return SourceRequestContext(
                source = source,
                allowedBrowserOrigins = baseOrigin?.let(::setOf).orEmpty(),
            )
        }

        fun from(source: ContentSource, declaredBaseUrl: String): SourceRequestContext = SourceRequestContext(
            source = source,
            allowedBrowserOrigins = declaredBaseUrl.toHttpsOrigin()?.let(::setOf).orEmpty(),
        )
    }
}

private fun String.toHttpsOrigin(): String? {
    val url = toHttpUrlOrNull()?.takeIf { it.scheme == "https" } ?: return null
    return buildString {
        append(url.scheme)
        append("://")
        append(url.host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) {
            append(':')
            append(url.port)
        }
    }
}
