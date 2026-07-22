package org.skepsun.kototoro.reader.ui

interface ReaderNavigator {
	val isReaderResumed: Boolean

	fun switchPageBy(delta: Int)

	fun switchPageTo(position: Int, smooth: Boolean)

	fun scrollBy(delta: Int, smooth: Boolean): Boolean

	fun getCurrentState(): ReaderState?

	fun onZoomIn()

	fun onZoomOut()
}
