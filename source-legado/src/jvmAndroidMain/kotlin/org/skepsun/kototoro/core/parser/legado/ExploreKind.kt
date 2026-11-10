package org.skepsun.kototoro.core.parser.legado

import kotlinx.serialization.Serializable

/**
 * Explore category definition for Legado sources.
 * Matches Legado's ExploreKind format.
 */
@Serializable
data class ExploreKind(
    val title: String = "",
    val url: String? = null
)
