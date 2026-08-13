package org.skepsun.kototoro.settings.reader

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import java.util.EnumMap
import javax.inject.Inject

@HiltViewModel
class ReaderTapGridConfigViewModel @Inject constructor(
	private val tapGridSettings: TapGridSettings,
) : BaseViewModel() {

	val content = tapGridSettings.observeChanges()
		.onStart { emit(null) }
		.map { getData() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyMap())

	fun reset() {
		tapGridSettings.reset()
	}

	fun disableAll() {
		tapGridSettings.disableAll()
	}

	fun setTapAction(area: TapGridArea, isLongTap: Boolean, action: TapAction?) {
		// 设置项很小，直接提交可确保离开页面前动作（尤其是“无”）已持久化。
		tapGridSettings.setTapAction(area, isLongTap, action)
	}

	private fun getData(): Map<TapGridArea, TapActions> {
		val map = EnumMap<TapGridArea, TapActions>(TapGridArea::class.java)
		for (area in TapGridArea.entries) {
			map[area] = TapActions(
				tapAction = tapGridSettings.getTapAction(area, isLongTap = false),
				longTapAction = tapGridSettings.getTapAction(area, isLongTap = true),
			)
		}
		return map
	}

	data class TapActions(
		val tapAction: TapAction?,
		val longTapAction: TapAction?,
	)
}
