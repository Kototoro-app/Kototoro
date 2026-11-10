package org.skepsun.kototoro.core.parser.legado

import org.json.JSONObject

/** URL resolution rules shared by Android and desktop. */
object LegadoUrlRules {
    val optionsSplitRegex = Regex("\\s*,\\s*(?=\\{)")

    fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val splitMatch = optionsSplitRegex.find(url)
        if (splitMatch == null) {
            var normalized = url.trim()
            if (normalized.endsWith("/") && normalized.length > 8) {
                normalized = normalized.substring(0, normalized.length - 1)
            }
            return normalized
        }

        val urlPart = url.substring(0, splitMatch.range.first).trim()
        val optionsPart = url.substring(splitMatch.range.last + 1).trim()

        // 尝试解析 options；若是 POST/带 body，则把 options 的摘要拼到 key 上避免冲突
        val optionsKeySuffix = runCatching {
            val normalizedOptions = if (optionsPart.contains("'")) {
                optionsPart.replace("'", "\"")
            } else optionsPart
            val optionsJson = JSONObject(normalizedOptions)
            val method = optionsJson.optString("method", "GET").uppercase()
            val body = optionsJson.optString("body", "")
            if (method != "GET" || body.isNotBlank()) {
                val signature = buildString {
                    append(method)
                    if (body.isNotBlank()) {
                        append("|")
                        append(body)
                    }
                }
                "#opt=" + signature.hashCode().toUInt().toString(16)
            } else ""
        }.getOrDefault("")

        var normalized = urlPart + optionsKeySuffix

        // Remove trailing slash
        if (normalized.endsWith("/") && normalized.length > 8) {
            normalized = normalized.substring(0, normalized.length - 1)
        }

        return normalized
    }

    fun resolveUrl(base: String, relative: String): String {
        if (relative.isBlank()) return base
        if (relative.startsWith("http://") || relative.startsWith("https://") || relative.startsWith("data:")) {
            return relative
        }

        if (relative.startsWith("//")) {
            val protocol = if (base.startsWith("https")) "https" else "http"
            return "$protocol:$relative"
        }

        val host = getHost(base)
        if (host.isNotEmpty() && relative.startsWith(host)) {
            val protocol = if (base.startsWith("https")) "https" else "http"
            return "$protocol://$relative"
        }

        return try {
            java.net.URL(java.net.URL(base), relative).toString()
        } catch (e: Exception) {
            if (relative.startsWith("/")) {
                try {
                    val baseUri = java.net.URL(base)
                    "${baseUri.protocol}://${baseUri.host}$relative"
                } catch (_: Exception) {
                    relative
                }
            } else {
                relative
            }
        }
    }

    private fun getHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            java.net.URL(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
