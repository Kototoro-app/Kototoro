package org.skepsun.kototoro.video.ui

import org.skepsun.kototoro.parsers.model.Content

internal fun preferCompleteLaunchContent(
    stored: Content?,
    intent: Content?,
): Content? {
    if (stored == null) return intent
    if (intent == null) return stored
    return if (stored.chapters.isNullOrEmpty() && !intent.chapters.isNullOrEmpty()) {
        intent
    } else {
        stored
    }
}
