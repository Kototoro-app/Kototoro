package org.skepsun.kototoro.parsers.network

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAVAILABLE

public object CloudFlareHelper {

    public const val PROTECTION_NOT_DETECTED: Int = 0
    public const val PROTECTION_CAPTCHA: Int = 1
    public const val PROTECTION_BLOCKED: Int = 2

    private const val CF_CLEARANCE = "cf_clearance"

    public fun checkResponseForProtection(response: Response): Int {
        val cfRay = response.header("cf-ray")
        val server = response.header("server")
        val cfMitigated = response.header("cf-mitigated")
        val isCloudFlareServer = cfRay != null || server?.contains("cloudflare", ignoreCase = true) == true
        if (!isCloudFlareServer) {
            return PROTECTION_NOT_DETECTED
        }

        if (cfMitigated?.contains("challenge", ignoreCase = true) == true) {
            return PROTECTION_CAPTCHA
        }

        val hasChallengeStatus = response.code == HTTP_FORBIDDEN || response.code == HTTP_UNAVAILABLE
        val contentType = response.header("content-type").orEmpty()
        if (!hasChallengeStatus && !contentType.contains("html", ignoreCase = true)) {
            return PROTECTION_NOT_DETECTED
        }

        val content = try {
            response.peekBody(Long.MAX_VALUE).use {
                Jsoup.parse(it.byteStream(), Charsets.UTF_8.name(), response.request.url.toString())
            }
        } catch (_: IllegalStateException) {
            return PROTECTION_NOT_DETECTED
        }
        val html = content.html()
        return when {
            content.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null -> PROTECTION_BLOCKED
            content.selectFirst(".cf-error-details, #cf-error-details") != null -> PROTECTION_BLOCKED
            content.title().contains("Just a moment", ignoreCase = true) -> PROTECTION_CAPTCHA
            content.getElementById("challenge-error-title") != null -> PROTECTION_CAPTCHA
            content.getElementById("challenge-error-text") != null -> PROTECTION_CAPTCHA
            hasChallengeStatus -> when {
                html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) -> PROTECTION_CAPTCHA
                html.contains("cf-browser-verification", ignoreCase = true) -> PROTECTION_CAPTCHA
                html.contains("__cf_chl_opt", ignoreCase = true) -> PROTECTION_CAPTCHA
                html.contains("cf_chl_", ignoreCase = true) -> PROTECTION_CAPTCHA
                html.contains("challenge-form", ignoreCase = true) -> PROTECTION_CAPTCHA
                else -> PROTECTION_NOT_DETECTED
            }

            else -> PROTECTION_NOT_DETECTED
        }
    }



    public fun getClearanceCookie(cookieJar: CookieJar, url: String): String? {
        return cookieJar.loadForRequest(url.toHttpUrl()).find { it.name == CF_CLEARANCE }?.value
    }

    public fun isCloudFlareCookie(name: String): Boolean {
        return name.startsWith("cf_")
            || name.startsWith("_cf")
            || name.startsWith("__cf")
            || name == "csrftoken"
    }

    @JvmStatic
    public fun getChallengeUrl(url: String): String {
        val httpUrl = try {
            url.toHttpUrl()
        } catch (_: Exception) {
            return url
        }
        val host = httpUrl.host.lowercase()
        
        // If it's a known asset host or ends with static extensions, resolve to the parent root domain
        val isAsset = httpUrl.encodedPath.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "svg") ||
                host.startsWith("imagenes.") || host.startsWith("images.") || host.startsWith("cdn.") || host.startsWith("img.") || host.startsWith("static.")
        
        if (isAsset) {
            val rootDomain = getRootDomain(host)
            if (rootDomain.isNotBlank()) {
                return "https://$rootDomain/"
            }
        }
        
        // Otherwise, strip path/query parameters to hit the root of the host
        return httpUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    @JvmStatic
    public fun getRootDomain(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val last = parts.last()
        val secondLast = parts[parts.size - 2]
        val isDoubleTld = secondLast in setOf("com", "co", "org", "net", "edu", "gov") && last.length == 2
        return if (isDoubleTld && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }
}
