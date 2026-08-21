package org.skepsun.kototoro.core.network

import okhttp3.Request

internal fun Request.browserTransportHeaders(): Map<String, String> = buildMap {
    headers.forEach { (name, value) -> put(name, value) }
    if (keys.none { it.equals("Content-Type", ignoreCase = true) }) {
        body?.contentType()?.toString()?.let { put("Content-Type", it) }
    }
}
