package org.skepsun.kototoro.core.network

import android.content.Context
import android.webkit.WebSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug

object UserAgentProvider {
    private var cachedUserAgent: String? = null

    @JvmStatic
    fun get(context: Context): String {
        cachedUserAgent?.let { return it }
        val rawUA = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (e: Exception) {
            e.printStackTraceDebug()
            // Fallback Chrome User Agent if WebView is not ready/available
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.203 Mobile Safari/537.36"
        }
        val sanitized = sanitize(rawUA)
        cachedUserAgent = sanitized
        return sanitized
    }

    @JvmStatic
    fun sanitize(userAgent: String): String {
        return userAgent
            .replace(Regex("; wv\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Version/\\d+\\.\\d+\\s?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[[a-zA-Z0-9._]+\\]\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
