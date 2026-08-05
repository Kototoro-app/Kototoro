package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow

val LocalScrollToTopEvents = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

@Composable
fun ScrollToTopEffect(onScrollToTop: suspend () -> Unit) {
    val events = LocalScrollToTopEvents.current
    val currentOnScrollToTop by rememberUpdatedState(onScrollToTop)

    LaunchedEffect(events) {
        events.collectLatest {
            currentOnScrollToTop()
        }
    }
}
