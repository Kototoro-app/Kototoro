package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.ui.adaptive.tvSliderDpad
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.core.ReaderAutoScroll

/** Shared speed draft and multiplier. Android applies live changes; desktop persists once the gesture finishes. */
@Composable
fun ReaderAutoScrollSpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    commitOnRelease: Boolean = true,
    title: String = uiString("speed"),
    sliderTag: String = "reader-autoscroll-speed",
) {
    var draft by remember(speed) { mutableFloatStateOf(speed.coerceIn(0f, 1f)) }
    var pendingCommit by remember(speed) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f))
            Text(uiString("speed_value", ReaderAutoScroll.speedMultiplier(draft)),
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = draft,
            onValueChange = {
                draft = it.coerceIn(0f, 1f)
                pendingCommit = commitOnRelease
                if (!commitOnRelease) onSpeedChange(draft)
            },
            onValueChangeFinished = {
                if (enabled && pendingCommit) onSpeedChange(draft)
                pendingCommit = false
            },
            valueRange = 0f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(sliderTag).tvSliderDpad(
                value = draft,
                valueRange = 0f..1f,
                // Each remote key press is a complete edit; it does not produce a slider release callback.
                onValueChange = { pendingCommit = false; draft = it; onSpeedChange(it) },
                enabled = enabled,
            ),
        )
    }
}
