package org.skepsun.kototoro.core.model

import java.util.Locale

val ADULT_TAG_KEYWORDS = setOf(
    "adult",
    "hentai",
    "18+",
    "nsfw",
    "mature",
    "ecchi",
    "smut",
    "explicit",
    "r18",
    "r-18",
)

val SAFE_TAG_KEYWORDS = setOf(
    "safe",
    "all ages",
    "non-h",
    "sfw",
    "非h",
    "正常向",
    "全年龄",
    "全年龄向",
)

fun String.isAdultTagKeyword(): Boolean {
    return trim().lowercase(Locale.ROOT) in ADULT_TAG_KEYWORDS
}

fun String.containsAdultTagKeyword(): Boolean {
    val normalized = trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) {
        return false
    }
    return ADULT_TAG_KEYWORDS.any { keyword -> normalized.contains(keyword) }
}

fun String.isExplicitlySafeTagKeyword(): Boolean {
    return trim().lowercase(Locale.ROOT) in SAFE_TAG_KEYWORDS
}
