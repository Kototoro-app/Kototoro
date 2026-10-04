package org.skepsun.kototoro.core.source

/** Existing Android identities must remain stable when the same extension runs inside the JVM host. */
object MihonModelRules {
    private val safeTags = setOf("safe", "all ages", "non-h", "sfw", "非h", "正常向", "全年龄", "全年龄向")
    private val adultTags = setOf("adult", "hentai", "18+", "nsfw", "mature", "ecchi", "smut", "explicit", "r18", "r-18")

    fun contentRating(isNsfw: Boolean, explicit: String?, genres: List<String>): String? = when {
        isNsfw -> "ADULT"
        explicit in setOf("SAFE", "SUGGESTIVE", "ADULT") -> explicit
        genres.any { it.lowercase() in safeTags } -> "SAFE"
        genres.any { it.trim().lowercase() in adultTags } -> "ADULT"
        else -> null
    }

    fun contentId(url: String, sourceName: String, title: String): Long {
        val identity = url.ifBlank { title.ifBlank { "unknown" } }
        var hash = 1125899906842597L
        for (character in "$sourceName|manga|$identity") hash = 31 * hash + character.code
        return hash and Long.MAX_VALUE
    }

    fun chapterId(url: String, sourceName: String, parentUrl: String? = null): Long {
        val identity = if (parentUrl == null) "$sourceName|chapter|$url"
        else "$sourceName|chapter|$parentUrl|$url"
        return identity.hashCode().toLong() and Long.MAX_VALUE
    }

    fun pageId(chapterIdentity: String, index: Int): Long =
        "$chapterIdentity|page|$index".hashCode().toLong() and Long.MAX_VALUE

    fun resolveUrl(baseUrl: String, url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        baseUrl.isNotBlank() -> baseUrl.trimEnd('/') + "/" + url.trimStart('/')
        else -> url
    }

    /** Preserve the existing Android cover-request policy without replacing a source-declared Referer. */
    fun coverReferer(imageUrl: String, declaredReferer: String?): String? =
        if (declaredReferer == null &&
            (imageUrl.contains("hitomi.la") || imageUrl.contains("gold-usergeneratedcontent.net"))) {
            "https://hitomi.la/"
        } else null

    /** ID-only URLs are deliberately kept unchanged: several extensions use them as API parameters. */
    fun mangaUrl(baseUrl: String, url: String): String {
        val embeddedProtocol = url.indexOf("http", startIndex = 1)
        var result = if (embeddedProtocol > 0) url.substring(embeddedProtocol) else url
        result = result.replace(Regex("^(https?)/+"), "$1://")
        if (baseUrl.isNotBlank() && result.startsWith(baseUrl.trimEnd('/'))) {
            val stripped = result.substring(baseUrl.trimEnd('/').length)
            if (stripped.startsWith('/') || stripped.isEmpty()) result = stripped
        }
        return result
    }

    fun cleanGenre(value: String): String {
        val match = Regex("""^\w+\((\w+)=([^,)]+)""").find(value)
        if (match != null) return match.groupValues[2]
        return if (value.matches(Regex("""^\w+=[^,)]+\)?$"""))) "" else value
    }
}
