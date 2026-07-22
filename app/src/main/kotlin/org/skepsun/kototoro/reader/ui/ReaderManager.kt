package org.skepsun.kototoro.reader.ui

import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import androidx.core.view.children
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderController

internal class ReaderManager(
	private val container: ViewGroup,
	settings: AppSettings,
	private val composeReader: ComposeReaderController,
) {

	private var isDoublePage = isLandscape() && settings.isReaderDoubleOnLandscape

	init {
		container.children.toList().forEach(container::removeView)
		container.addView(
			composeReader.view,
			ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
		)
	}

	val currentReader: ReaderNavigator = composeReader

	var currentMode: ReaderMode? = null
		private set

	fun replace(newMode: ReaderMode) {
		Log.d(LOG_TAG, "replace: newMode=$newMode, currentMode=$currentMode")
		currentMode = newMode
		composeReader.updateConfiguration(newMode, isDoublePage)
	}

	fun setDoubleReaderMode(isEnabled: Boolean) {
		isDoublePage = isEnabled
		currentMode?.let { composeReader.updateConfiguration(it, isDoublePage) }
	}

	private fun isLandscape() =
		container.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

	private companion object {
		const val LOG_TAG = "ReaderDebug"
	}
}
