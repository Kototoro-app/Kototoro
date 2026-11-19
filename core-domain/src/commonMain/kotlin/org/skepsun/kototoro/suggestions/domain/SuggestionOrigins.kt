package org.skepsun.kototoro.suggestions.domain

/**
 * The slice of the suggestions a work belongs to for balancing: its kind (comic, novel, video) and, for comics, where it comes
 * from. Source languages say little about it, since Chinese-language sources mostly host Japanese manga, so the source's
 * declared type and the work's own tags come first and the source language is only the last resort.
 */
object SuggestionOrigins {
    private val korean = listOf("manhwa", "webtoon", "웹툰", "韩漫", "韓漫", "韩国", "韓國", "korean")
    private val chinese = listOf("manhua", "国漫", "國漫", "国产", "國產", "中国", "中國", "chinese", "donghua", "条漫")
    private val japanese = listOf("manga", "日漫", "日本", "japanese", "doujinshi", "同人")

    private fun kindOf(contentType: String?): String = when (contentType?.uppercase()) {
        "NOVEL", "HENTAI_NOVEL" -> "novel"
        "VIDEO", "HENTAI_VIDEO" -> "video"
        else -> "comic"
    }

    private fun originOf(contentType: String?, tags: Collection<String>, locale: String?): String {
        when (contentType?.uppercase()) {
            "MANHWA" -> return "kr"
            "MANHUA" -> return "cn"
            "COMICS" -> return "other"
        }
        val lowered = tags.map { it.lowercase() }
        fun has(words: List<String>) = lowered.any { tag -> words.any { it in tag } }
        return when {
            has(korean) -> "kr"
            has(chinese) -> "cn"
            has(japanese) -> "jp"
            else -> when (locale?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
                "ko" -> "kr"
                "zh" -> "cn"
                "ja" -> "jp"
                else -> "other"
            }
        }
    }

    /** `comic/kr`, `comic/cn`, `comic/jp`, `comic/other`, `novel` or `video`. */
    fun groupOf(contentType: String?, tags: Collection<String>, locale: String?): String {
        val kind = kindOf(contentType)
        return if (kind == "comic") "comic/" + originOf(contentType, tags, locale) else kind
    }
}
