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

/** Resolves the underlying media family for the given content type name. */
fun resolveMediaFamily(contentTypeName: String?): DetailsMediaStyle = when (contentTypeName) {
    "VIDEO", "HENTAI_VIDEO" -> DetailsMediaStyle.VIDEO
    "NOVEL", "HENTAI_NOVEL" -> DetailsMediaStyle.NOVEL
    else -> DetailsMediaStyle.MANGA
}

/** Resolves the dedicated presentation style tailored specifically for this media family. */
fun resolveDedicatedDetailsMediaStyle(contentTypeName: String?): DetailsMediaStyle =
    resolveMediaFamily(contentTypeName)

/**
 * Resolves [DetailsMediaStyle] for a content type, defaulting to the original classic style ([DetailsMediaStyle.MANGA])
 * unless overridden by user preference.
 */
fun resolveDetailsMediaStyle(
    contentTypeName: String?,
    overrideStyle: DetailsMediaStyle? = null,
): DetailsMediaStyle = overrideStyle ?: DetailsMediaStyle.MANGA

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
    val actualKind = resolveMediaFamily(contentTypeName)
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
