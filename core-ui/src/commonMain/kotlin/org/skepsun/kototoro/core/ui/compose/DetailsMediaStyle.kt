package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Immutable

/**
 * Distinct presentation style for the three primary media families.
 * Android and desktop share these style decisions so Manga, Novel and Video details keep coherent visual identities.
 */
enum class DetailsMediaStyle {
    MANGA,
    NOVEL,
    VIDEO,
}

/** Parses a persisted [DetailsMediaStyle] name, returning `null` when unrecognized or unset. */
fun parseDetailsMediaStyleOrNull(raw: String?): DetailsMediaStyle? =
    DetailsMediaStyle.entries.firstOrNull { it.name == raw }

/** Resolves [DetailsMediaStyle] from a `ContentType` enum name, optionally overridden by user preference. */
fun resolveDetailsMediaStyle(
    contentTypeName: String?,
    overrideStyle: DetailsMediaStyle? = null,
): DetailsMediaStyle = overrideStyle ?: when (contentTypeName) {
    "VIDEO", "HENTAI_VIDEO" -> DetailsMediaStyle.VIDEO
    "NOVEL", "HENTAI_NOVEL" -> DetailsMediaStyle.NOVEL
    else -> DetailsMediaStyle.MANGA
}

/** Shared layout & visual weighting policy derived from [DetailsMediaStyle]. */
@Immutable
data class DetailsMediaStylePolicy(
    val style: DetailsMediaStyle,
    val showPagesTab: Boolean,
    val showBookmarksTab: Boolean,
    val useVideoHeroBackdrop: Boolean,
    val useCenteredHeroHeader: Boolean,
    val useInlineEpisodePreview: Boolean,
    val dominantBackgroundStrength: Float,
    val synopsisCollapsedMaxLines: Int,
    val synopsisLineHeightScale: Float,
)

fun detailsMediaStylePolicy(
    contentTypeName: String?,
    styleOverride: DetailsMediaStyle? = null,
): DetailsMediaStylePolicy {
    val actualKind = resolveDetailsMediaStyle(contentTypeName, null)
    val style = resolveDetailsMediaStyle(contentTypeName, styleOverride)
    return detailsMediaStylePolicy(style).copy(
        showPagesTab = actualKind == DetailsMediaStyle.MANGA,
    )
}

fun detailsMediaStylePolicy(style: DetailsMediaStyle): DetailsMediaStylePolicy = when (style) {
    DetailsMediaStyle.VIDEO -> DetailsMediaStylePolicy(
        style = style,
        showPagesTab = false,
        showBookmarksTab = true,
        useVideoHeroBackdrop = true,
        useCenteredHeroHeader = true,
        useInlineEpisodePreview = true,
        dominantBackgroundStrength = 1.0f,
        synopsisCollapsedMaxLines = 3,
        synopsisLineHeightScale = 1.35f,
    )
    DetailsMediaStyle.NOVEL -> DetailsMediaStylePolicy(
        style = style,
        showPagesTab = false,
        showBookmarksTab = true,
        useVideoHeroBackdrop = false,
        useCenteredHeroHeader = false,
        useInlineEpisodePreview = false,
        dominantBackgroundStrength = 0.60f,
        synopsisCollapsedMaxLines = 4,
        synopsisLineHeightScale = 1.48f,
    )
    DetailsMediaStyle.MANGA -> DetailsMediaStylePolicy(
        style = style,
        showPagesTab = true,
        showBookmarksTab = true,
        useVideoHeroBackdrop = false,
        useCenteredHeroHeader = false,
        useInlineEpisodePreview = false,
        dominantBackgroundStrength = 0.85f,
        synopsisCollapsedMaxLines = 3,
        synopsisLineHeightScale = 1.35f,
    )
}
