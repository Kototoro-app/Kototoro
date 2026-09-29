package org.skepsun.kototoro.migration.domain

/**
 * Human-sized reading of a stored update error. Update checks persist the raw exception
 * text; showing it verbatim is noisy, so it is reduced to a few recognisable causes.
 */
sealed interface RefreshError {
    data object Challenge : RefreshError
    data class Http(val code: Int) : RefreshError
    data object Parse : RefreshError
    data object Network : RefreshError
    data class Other(val message: String) : RefreshError

    companion object {
        private const val MAX_MESSAGE = 60
        private val statusRegex = Regex("""(?:Status=|HTTP\s)(\d{3})""")
        private val classPrefixRegex = Regex("""^(?:[\w$]+\.)+[\w$]*(?:Exception|Error)\s*:\s*""")

        fun parse(raw: String?): RefreshError? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            return when {
                "cloudflare" in lower || "challenge_required" in lower || "captcha" in lower -> Challenge
                "parseexception" in lower -> Parse
                "timeout" in lower || "unknownhost" in lower || "connectexception" in lower ||
                    "sslhandshake" in lower -> Network
                else -> statusRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let(::Http)
                    ?: Other(shorten(text))
            }
        }

        private fun shorten(text: String): String {
            val message = text.lineSequence().first().replace(classPrefixRegex, "").trim().ifEmpty { text }
            return if (message.length > MAX_MESSAGE) message.take(MAX_MESSAGE) + "…" else message
        }
    }
}
