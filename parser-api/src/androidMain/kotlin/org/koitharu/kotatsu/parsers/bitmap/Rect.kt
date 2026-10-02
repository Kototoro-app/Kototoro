package org.koitharu.kotatsu.parsers.bitmap

public data class Rect(
	val left: Int = 0,
	val top: Int = 0,
	val right: Int = 0,
	val bottom: Int = 0,
) {

	val width: Int
		get() = right - left

	val height: Int
		get() = bottom - top
}
