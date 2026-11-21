package org.skepsun.kototoro.core.ui.suggestions

import org.skepsun.kototoro.core.ui.text.UiStrings
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection
import org.skepsun.kototoro.suggestions.domain.suggestionReasonLabel

fun suggestionReasonText(strings: UiStrings, direction: SuggestionDirection, reason: String?, language: String): String {
    val label = reason?.let { suggestionReasonLabel(it, language) }
    return when (direction) {
        SuggestionDirection.TASTE -> if (label == null) strings.string("suggestion_reason_default")
            else strings.format("suggestion_reason_taste", label)
        SuggestionDirection.EXPLORE -> if (label == null) strings.string("suggestion_description_explore")
            else strings.format("suggestion_reason_explore", label)
        SuggestionDirection.POPULAR -> strings.string("suggestion_reason_popular")
        SuggestionDirection.NEW -> strings.string("suggestion_reason_new")
        SuggestionDirection.COMMUNITY -> if (reason.isNullOrBlank()) strings.string("community_for_you")
            else strings.format("community_because", reason)
    }
}
