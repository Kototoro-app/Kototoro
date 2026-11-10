package org.skepsun.kototoro.core.parser.legado

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.publicsuffix.PublicSuffixDatabase

object LegadoNetworkUtils {
    /**
     * Get TLD+1 domain from URL
     * Based on Legado's getSubDomain logic
     */
    fun getSubDomain(url: String): String {
        return try {
            val host = url.toHttpUrl().host
            // If it's an IP address, return host as is
            if (host.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) {
                return host
            }
            // Use OkHttp's PublicSuffixDatabase to get TLD+1
            PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) ?: host
        } catch (e: Exception) {
            // Fallback to extraction if URL parsing fails
            extractHost(url)
        }
    }

    private fun extractHost(url: String): String {
        return try {
            val withoutProtocol = url.substringAfter("://")
            val host = withoutProtocol.substringBefore("/").substringBefore(":")
            host
        } catch (e: Exception) {
            url
        }
    }
}
