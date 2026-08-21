package org.skepsun.kototoro.core.network.webview

private val BROWSER_MANAGED_REQUEST_HEADERS = setOf(
    "accept-encoding",
    "connection",
    "content-length",
    "cookie",
    "host",
    "origin",
    "referer",
    "user-agent",
)

internal fun browserFetchHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys { name ->
    val normalizedName = name.lowercase()
    normalizedName !in BROWSER_MANAGED_REQUEST_HEADERS &&
        !normalizedName.startsWith("sec-fetch-") &&
        !normalizedName.startsWith("sec-ch-ua")
}
