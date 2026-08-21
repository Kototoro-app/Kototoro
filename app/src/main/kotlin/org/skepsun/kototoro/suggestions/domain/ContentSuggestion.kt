package org.skepsun.kototoro.suggestions.domain

import androidx.annotation.FloatRange
import org.skepsun.kototoro.parsers.model.Content

data class ContentSuggestion(
    val manga: Content,
    @FloatRange(from = 0.0, to = 1.0)
    val relevance: Float,
)