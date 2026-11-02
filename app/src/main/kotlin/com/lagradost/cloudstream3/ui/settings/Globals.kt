package com.lagradost.cloudstream3.ui.settings

/**
 * Cloudstream's layout flags, which plugins test to adapt their dialogs to a TV (`isLayout(TV)`). Same constants and
 * `isLayout` as the official app; the host answers which layout it is in through [layoutProvider], as it has no
 * Cloudstream layout setting of its own. Without a host answer the layout is a phone.
 */
object Globals {
	const val PHONE: Int = 0b00001
	const val TV: Int = 0b00010
	const val EMULATOR: Int = 0b00100

	/** The current layout, one of [PHONE], [TV] or [EMULATOR]; asked on every check, so it follows the host's mode. */
	@Volatile
	var layoutProvider: () -> Int = { PHONE }

	/** True if the layout is any of [flags], so `isLayout(TV or EMULATOR)` asks for a TV or the emulator layout. */
	fun isLayout(flags: Int): Boolean = (layoutProvider() and flags) != 0
}
