package org.skepsun.kototoro.space.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Identifies which feature owns the commands shown by the persistent Cockpit. */
enum class CockpitPageContext {
	MAIN,
	CONTENT_LIST,
	DETAILS,
	MANGA_READER,
	NOVEL_READER,
	VIDEO_PLAYER,
}

/**
 * Presentation model for one contextual command. Business state and execution remain owned by the
 * page that creates the command; the Cockpit only renders and dispatches it.
 */
@Immutable
data class CockpitCommand(
	val id: String,
	@DrawableRes val iconRes: Int,
	@StringRes val titleRes: Int,
	val iconRotationDegrees: Float = 0f,
	val enabled: Boolean = true,
	val selected: Boolean = false,
	val onClick: () -> Unit,
)

internal fun resolveCockpitPageContext(
	hasEmbeddedMangaReader: Boolean,
	isDetailsRoute: Boolean,
	isContentListRoute: Boolean,
): CockpitPageContext = when {
	hasEmbeddedMangaReader -> CockpitPageContext.MANGA_READER
	isDetailsRoute -> CockpitPageContext.DETAILS
	isContentListRoute -> CockpitPageContext.CONTENT_LIST
	else -> CockpitPageContext.MAIN
}

/** Stable, explainable first-pass score; richer progress/recency signals can be added at the source. */
internal fun resolveCockpitMomentumScore(
	canResume: Boolean,
	newChapters: Int,
): Int = (if (canResume) 100 else 0) + newChapters.coerceIn(0, 20) * 4
