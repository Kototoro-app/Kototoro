package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Immutable

/**
 * Distinct presentation for the three primary media families, chosen by the content type.
 * Android and desktop share these style decisions so Manga, Novel and Video details keep coherent visual identities.
 */
enum class DetailsMediaStyle {
    MANGA,
    NOVEL,
    VIDEO,
}

/** Resolves the media family, and with it the details presentation, for the given content type name. */
fun resolveMediaFamily(contentTypeName: String?): DetailsMediaStyle = when (contentTypeName) {
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

/**
 * The details presentation for a content type. It follows the content type alone: the per-type style choice was
 * removed, so a video is always shown as a video and a novel as a book (the panorama switch still decides whether a
 * video gets the centred cinema header).
 */
fun detailsMediaStylePolicy(contentTypeName: String?): DetailsMediaStylePolicy =
    detailsMediaStylePolicy(resolveMediaFamily(contentTypeName))

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
