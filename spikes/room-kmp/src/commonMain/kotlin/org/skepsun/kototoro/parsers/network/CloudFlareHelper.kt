package org.skepsun.kototoro.parsers.network

/** SPIKE stand-in: only the persisted protection-state constants; detection logic stays with OkHttp/Jsoup. */
object CloudFlareHelper {
    const val PROTECTION_NOT_DETECTED: Int = 0
    const val PROTECTION_CAPTCHA: Int = 1
    const val PROTECTION_BLOCKED: Int = 2
}
