package org.skepsun.kototoro.core.network.webview

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class BrowserOriginPolicy private constructor(
    val primaryOrigin: String,
	val documentOrigins: Set<String>,
	val fetchOrigins: Set<String>,
	val redirectOrigins: Set<String>,
) {
	fun allowsDocument(url: String?): Boolean = allows(url, documentOrigins)

	fun allowsFetch(url: String?): Boolean = allows(url, fetchOrigins)

	fun allowsRedirect(url: String?): Boolean = allows(url, redirectOrigins)

	private fun allows(url: String?, origins: Set<String>): Boolean {
        val httpUrl = url?.toHttpUrlOrNull() ?: return false
		return httpUrl.scheme == HTTPS_SCHEME && httpUrl.host.isSafeBrowserHost() && httpUrl.toOrigin() in origins
    }

    companion object {
        private const val HTTPS_SCHEME = "https"

        fun create(
            requestUrl: String,
            additionalOrigins: Set<String> = emptySet(),
        ): BrowserOriginPolicy? {
            val target = requestUrl.toHttpUrlOrNull()?.takeIf {
				it.scheme == HTTPS_SCHEME && it.host.isSafeBrowserHost()
			} ?: return null
            val normalizedAdditional = additionalOrigins.map { origin ->
                val url = origin.toHttpUrlOrNull()?.takeIf {
					it.scheme == HTTPS_SCHEME && it.host.isSafeBrowserHost()
				} ?: return null
                if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
                url.toOrigin()
            }
            val primaryOrigin = target.toOrigin()
            return BrowserOriginPolicy(
                primaryOrigin = primaryOrigin,
				documentOrigins = setOf(primaryOrigin),
				fetchOrigins = buildSet {
                    add(primaryOrigin)
                    addAll(normalizedAdditional)
                },
				redirectOrigins = setOf(primaryOrigin),
            )
        }
    }
}

private fun String.isSafeBrowserHost(): Boolean {
	val normalized = lowercase().removeSuffix(".")
	if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized == "local") return false
	if (normalized.contains(":")) {
		return normalized != "::1" && !normalized.startsWith("fe80:") && !normalized.startsWith("fc") && !normalized.startsWith("fd")
	}
	val octets = normalized.split('.')
	if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return true
	val a = octets[0].toInt()
	val b = octets[1].toInt()
	return when {
		a == 10 || a == 127 -> false
		a == 172 && b in 16..31 -> false
		a == 192 && b == 168 -> false
		a == 169 && b == 254 -> false
		a == 0 -> false
		else -> true
	}
}

private fun HttpUrl.toOrigin(): String = buildString {
    append(scheme)
    append("://")
    append(host)
    if (port != HttpUrl.defaultPort(scheme)) {
        append(':')
        append(port)
    }
}
