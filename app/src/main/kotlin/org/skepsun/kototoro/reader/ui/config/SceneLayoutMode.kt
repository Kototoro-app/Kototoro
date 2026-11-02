package org.skepsun.kototoro.reader.ui.config

import org.skepsun.kototoro.core.prefs.ReaderMode

/**
 * Which layout actually renders the pages for the current presentation.
 *
 * `CONTINUOUS_HORIZONTAL` fits every page to the viewport *height* and lays the chapter out as a
 * filmstrip along the reading direction. On a phone that is what the mode promises, but a
 * television viewport is landscape and far wider than a manga page: for a tall page the strip
 * collapses into a column of slivers hugging the reading edge with the rest of the screen empty
 * (a 960x7200 page on a 1920x1080 screen is 144 px wide, so four pages cover 576 px of 1920).
 * The presentation therefore lays continuous reading out as one full-width vertical column, which
 * is also the only continuous layout in which `pageWidthPx == viewportWidth`.
 *
 * Only the video-driven continuous mode is remapped. Paged modes keep their fit semantics: a
 * paged page is letterboxed by definition, and widening it on a television would put the lower
 * half of the page out of reach, because the TV key policy scrolls continuous scenes vertically
 * and only turns pages in paged ones.
 *
 * Phones and tablets keep exactly the mode the user picked, including continuous horizontal.
 */
fun resolveSceneLayoutMode(
	mode: ReaderMode?,
	isTv: Boolean,
): ReaderMode? = if (isTv && mode == ReaderMode.CONTINUOUS_HORIZONTAL) {
	ReaderMode.WEBTOON
} else {
	mode
}
