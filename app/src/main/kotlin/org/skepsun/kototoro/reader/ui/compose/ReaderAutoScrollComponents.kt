package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.reader.ui.compose.design.ReaderAutoScrollSpeedControl

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderAutoScrollPanel(state: ReaderAutoScrollUiState, callbacks: ReaderAutoScrollCallbacks) {
    var startSwitchHasFocus by remember { mutableStateOf(false) }
    val startSwitchRequester = rememberTvSheetStartFocus { startSwitchHasFocus }
    Column(
        // A television draws this sheet in landscape, where the stack of rows is taller than the
        // screen: without a scroll the last switches are off-screen and a remote cannot drag the
        // sheet up. Focus inside the column brings its row into view on its own.
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.reader_autoscroll),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = callbacks.onClose,
                modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
            ) { Text("×", style = MaterialTheme.typography.titleLarge) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll), modifier = Modifier.weight(1f))
            Switch(
                checked = state.active,
                onCheckedChange = callbacks.onActiveChanged,
                modifier = Modifier
                    .focusRequester(startSwitchRequester)
                    .onFocusChanged { startSwitchHasFocus = it.isFocused }
                    .tvFocusable(shape = CircleShape, addFocusTarget = false),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.manuallyPaused) stringResource(R.string.play) else stringResource(R.string.pause),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = !state.manuallyPaused,
                onCheckedChange = { callbacks.onPausedChanged(!it) },
                modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
            )
        }
        ReaderAutoScrollSpeedControl(
            speed = state.speed,
            onSpeedChange = callbacks.onSpeedChanged,
            commitOnRelease = false,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll_fab), modifier = Modifier.weight(1f))
            Switch(
                checked = state.fabVisible,
                onCheckedChange = callbacks.onFabChanged,
                modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll_pause_on_ui), modifier = Modifier.weight(1f))
            Switch(
                checked = state.pauseOnUi,
                onCheckedChange = callbacks.onPauseOnUiChanged,
                modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
            )
        }
        if (state.showPageDelay) {
            Text(
                stringResource(R.string.page_switch_timer, state.pageDelaySeconds),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun ReaderAutoScrollFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-scroll affordance: a floating glass pill (like the reader's
    // other pill chrome) — uniform hairline + press gloss, no resting
    // highlight.
    GlassSurface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        style = GlassDefaults.topBarChromeStyle(),
        componentRole = GlassComponentRole.PillControl,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().tvIconButtonRing(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_timer_run),
                contentDescription = stringResource(R.string.automatic_scroll),
            )
        }
    }
}
