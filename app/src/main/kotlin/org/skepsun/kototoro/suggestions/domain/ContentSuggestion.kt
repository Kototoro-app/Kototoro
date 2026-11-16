package org.skepsun.kototoro.suggestions.domain

import androidx.annotation.FloatRange
import org.skepsun.kototoro.parsers.model.Content

data class ContentSuggestion(
    val manga: Content,
    @FloatRange(from = 0.0, to = 1.0)
    val relevance: Float,
    val direction: SuggestionDirection = SuggestionDirection.TASTE,
    /** The tag key it is suggested for, when the direction has one. */
    val reason: String? = null,
)