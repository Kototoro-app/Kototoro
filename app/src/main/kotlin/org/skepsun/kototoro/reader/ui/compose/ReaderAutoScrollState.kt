package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.core.prefs.AppSettings

@Immutable
data class ReaderAutoScrollUiState(
    val visible: Boolean = false,
    val active: Boolean = false,
    val manuallyPaused: Boolean = false,
    val speed: Float = AppSettings.DEFAULT_READER_AUTOSCROLL_SPEED,
    val fabVisible: Boolean = false,
    val pauseOnUi: Boolean = true,
    val showPageDelay: Boolean = false,
    val pageDelaySeconds: Long = 0L,
)

data class ReaderAutoScrollCallbacks(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onActiveChanged: (Boolean) -> Unit = {},
    val onPausedChanged: (Boolean) -> Unit = {},
    val onSpeedChanged: (Float) -> Unit = {},
    val onFabChanged: (Boolean) -> Unit = {},
    val onPauseOnUiChanged: (Boolean) -> Unit = {},
)
