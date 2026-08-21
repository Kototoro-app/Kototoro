package org.skepsun.kototoro.core.parser.legado

import android.util.Log
import okhttp3.Response
import org.jsoup.Jsoup
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.parsers.model.ContentSource
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAVAILABLE

/**
 * Legado-specific CloudFlare resolver for sandbox mode.
 *
 * This resolver handles CloudFlare challenges internally within Legado code,
 * without depending on the core CloudFlareInterceptor.
 */
object LegadoCloudFlareResolver {

    private const val TAG = "LegadoCFResolver"

    const val PROTECTION_NOT_DETECTED = 0
    const val PROTECTION_CAPTCHA = 1
    const val PROTECTION_BLOCKED = 2

    /**
     * Check if a response is a CloudFlare challenge page.
     *
     * @param response The HTTP response to check
     * @param content The response body content (already parsed)
     * @return Protection type constant
     */
    fun checkResponseForProtection(response: Response, content: String?): Int {
        if (response.code != HTTP_FORBIDDEN && response.code != HTTP_UNAVAILABLE) {
            return PROTECTION_NOT_DETECTED
        }

        // Check headers for CloudFlare indicators
        val cfRay = response.header("cf-ray")
        val server = response.header("server")
        val cfMitigated = response.header("cf-mitigated")
        val isCloudFlareServer = cfRay != null || server?.contains("cloudflare", ignoreCase = true) == true

        if (!isCloudFlareServer) {
            return PROTECTION_NOT_DETECTED
        }

        // If cf-mitigated header contains "challenge", it's definitely a CF challenge
        if (cfMitigated?.contains("challenge", ignoreCase = true) == true) {
            Log.d(TAG, "CloudFlare challenge detected via cf-mitigated header")
            return PROTECTION_CAPTCHA
        }

        // Check content for CloudFlare indicators
        if (content != null) {
            try {
                val doc = Jsoup.parse(content)
                return when {
                    doc.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null -> {
                        Log.d(TAG, "CloudFlare BLOCKED detected")
                        PROTECTION_BLOCKED
                    }
                    doc.title().contains("Just a moment", ignoreCase = true) -> {
                        Log.d(TAG, "CloudFlare challenge detected via title")
                        PROTECTION_CAPTCHA
                    }
                    doc.getElementById("challenge-error-title") != null ||
                    doc.getElementById("challenge-error-text") != null -> {
                        Log.d(TAG, "CloudFlare challenge detected via challenge elements")
                        PROTECTION_CAPTCHA
                    }
                    else -> PROTECTION_NOT_DETECTED
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing content for CF detection", e)
            }
        }

        return PROTECTION_NOT_DETECTED
    }

    /**
     * Synchronize CloudFlare cookies across subdomains for a given URL.
     * This handles cases where verification happens on m. but requests happen on www.
     */
    fun syncCloudFlareCookies(url: String) {
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url) ?: return

            val cfCookies = cookies.split(";").map { it.trim() }.filter {
                it.startsWith("cf_clearance=") || it.startsWith("__cf_bm=")
            }

            if (cfCookies.isEmpty()) return

            val host = java.net.URL(url).host
            val scheme = java.net.URL(url).protocol
            val rootDomain = LegadoNetworkUtils.getSubDomain(url)

            val domainsToSync = listOf(
                rootDomain,
                ".$rootDomain"
            )

            cfCookies.forEach { cookie ->
                domainsToSync.forEach { domain ->
                    // Set cookie using the actual host as the URL base but with specified domain
                    cookieManager.setCookie("https://$host", "$cookie; Domain=$domain; Path=/")
                    if (scheme == "http") {
                        cookieManager.setCookie("http://$host", "$cookie; Domain=$domain; Path=/")
                    }
                }
            }
            cookieManager.flush()
            Log.d(TAG, "Synchronized ${cfCookies.size} CF cookies for domains: $domainsToSync")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync CF cookies", e)
        }
    }

    /**
     * Check if response is a CloudFlare challenge.
     * Simplified version that also reads response body.
     */
    fun isCfChallenge(response: Response, content: String?): Boolean {
        return checkResponseForProtection(response, content) == PROTECTION_CAPTCHA
    }

    /**
     * Create a CloudFlareProtectedException for the given URL and source.
     */
    fun createException(url: String, source: ContentSource, headers: okhttp3.Headers): CloudFlareProtectedException {
        return CloudFlareProtectedException(
            url = url,
            source = source,
            headers = headers
        )
    }
}
