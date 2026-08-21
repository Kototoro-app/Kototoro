package org.skepsun.kototoro.extensions.runtime

fun getExternalExtensionLanguageDisplayName(langCode: String): String {
    return when (langCode.lowercase()) {
        "zh" -> "中文"
        "zh-hans" -> "简体中文"
        "zh-hant" -> "繁體中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "es" -> "Español"
        "pt" -> "Português"
        "pt-br" -> "Português (Brasil)"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "it" -> "Italiano"
        "ru" -> "Русский"
        "th" -> "ไทย"
        "vi" -> "Tiếng Việt"
        "id" -> "Bahasa Indonesia"
        "ar" -> "العربية"
        "tr" -> "Türkçe"
        "pl" -> "Polski"
        "all" -> "Multi"
        else -> langCode.uppercase()
    }
}
