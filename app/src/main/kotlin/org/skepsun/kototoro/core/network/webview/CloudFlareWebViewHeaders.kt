package org.skepsun.kototoro.core.network.webview

import okhttp3.Headers
import org.skepsun.kototoro.core.network.CommonHeaders

internal fun Headers.toCloudFlareWebViewHeaders(): Map<String, String> = names()
    .filterNot {
        val normalized = it.lowercase()
        normalized in WEBVIEW_MANAGED_OR_UNSAFE_HEADERS || normalized.startsWith("proxy-")
    }
    .associateWith { get(it).orEmpty() }
    .filterValues { it.isNotEmpty() }

private val WEBVIEW_MANAGED_OR_UNSAFE_HEADERS = setOf(
    "accept-encoding",
    "authorization",
    "connection",
    "content-length",
    "cookie",
    "cookie2",
    "host",
    "keep-alive",
    "proxy-authorization",
    "set-cookie",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "user-agent",
    CommonHeaders.MANGA_SOURCE.lowercase(),
)
