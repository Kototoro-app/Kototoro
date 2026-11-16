package org.skepsun.kototoro.suggestions.domain

/** Source language, shared by candidate fetching and cached recommendations. Stored by name. */
enum class SuggestionLanguage {
    SYSTEM, CHINESE, ENGLISH, JAPANESE, ALL;

    fun accepts(locale: String?, systemLanguage: String = "en"): Boolean {
        val language = locale.orEmpty().trim().lowercase().substringBefore('-').substringBefore('_')
        return when (this) {
            SYSTEM -> language == systemLanguage.lowercase().substringBefore('-').substringBefore('_')
            CHINESE -> language in setOf("zh", "zho", "chi", "cmn", "yue")
            ENGLISH -> language == "en"
            JAPANESE -> language == "ja"
            ALL -> true
        }
    }

    companion object {
        fun of(value: String?): SuggestionLanguage = entries.find { it.name == value } ?: SYSTEM
        const val PREFERENCE_KEY = "suggestions_language"
        const val ONLY_PREFERENCE_KEY = "suggestions_language_only"
    }
}
