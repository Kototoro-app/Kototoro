package org.skepsun.kototoro.core.prefs

import java.util.EnumSet

enum class ReaderControl {

	PREV_CHAPTER, NEXT_CHAPTER, SLIDER, PAGES_SHEET, SCREEN_ROTATION, SAVE_PAGE, TIMER, BOOKMARK, TRANSLATE, DOWNLOAD;

	companion object {

		val DEFAULT: Set<ReaderControl> = EnumSet.of(
			PREV_CHAPTER, NEXT_CHAPTER, SLIDER, PAGES_SHEET,
		)

		val FLOATING: Set<ReaderControl> = EnumSet.of(
			SCREEN_ROTATION,
			SAVE_PAGE,
			TIMER,
			BOOKMARK,
			TRANSLATE,
			DOWNLOAD,
		)

		val FLOATING_DEFAULT: Set<ReaderControl> = EnumSet.of(
			TRANSLATE,
		)

		const val MAX_FLOATING_CONTROLS = 4

		fun limitFloatingControls(controls: Set<ReaderControl>): Set<ReaderControl> {
			return FLOATING
				.asSequence()
				.filter(controls::contains)
				.take(MAX_FLOATING_CONTROLS)
				.toCollection(EnumSet.noneOf(ReaderControl::class.java))
		}
	}
}
