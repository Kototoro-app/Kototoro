package org.skepsun.kototoro.core.prefs

import java.util.EnumSet

enum class ReaderControl {

	PREV_CHAPTER, NEXT_CHAPTER, SLIDER, PAGES_SHEET, SCREEN_ROTATION, SAVE_PAGE, TIMER, BOOKMARK, TRANSLATE, DOWNLOAD;

	companion object {

		val DEFAULT: Set<ReaderControl> = EnumSet.of(
			PREV_CHAPTER, NEXT_CHAPTER, SLIDER, PAGES_SHEET,
		)

		val BOTTOM_BAR: Set<ReaderControl> = EnumSet.of(
			PAGES_SHEET,
			SCREEN_ROTATION,
			SAVE_PAGE,
			TIMER,
			BOOKMARK,
			TRANSLATE,
			DOWNLOAD,
		)

		val BOTTOM_BAR_DEFAULT: Set<ReaderControl> = EnumSet.of(
			PAGES_SHEET,
			TRANSLATE,
		)
	}
}
