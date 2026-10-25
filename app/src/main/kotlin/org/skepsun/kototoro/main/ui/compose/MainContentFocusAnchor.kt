package org.skepsun.kototoro.main.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * The first focus stop of the page currently shown under the top bar.
 *
 * The top bar is drawn as an overlay sibling of the page content, so a D-pad DOWN on a chrome stop finds
 * no candidate inside the page (the content column starts at the top of the window and swallows the whole
 * pressed direction). Pages therefore publish their first stop here while they are resumed, and the chrome
 * hands DOWN over with `tvDownFocusLink`.
 *
 * [requester] is observable so the bar picks up a new page's stop, and the slot holds at most one owner:
 * the page that registers last wins, while every page unregisters its own requester on pause or dispose.
 */
@Stable
class MainContentFocusAnchor {

    var requester: FocusRequester? by mutableStateOf(null)
        private set

    fun register(requester: FocusRequester) {
        this.requester = requester
    }

    fun unregister(requester: FocusRequester) {
        if (this.requester === requester) {
            this.requester = null
        }
    }
}

/** Null in hosts that do not run the app shell (previews, single-screen activities). */
val LocalMainContentFocusAnchor = staticCompositionLocalOf<MainContentFocusAnchor?> { null }

/**
 * Remembers a [FocusRequester] and publishes it into [LocalMainContentFocusAnchor] for as long as the page
 * that owns it is resumed. Attach the returned requester to the composable that should receive focus when the
 * top bar's search controls hand DOWN into the page:
 *
 * ```
 * val firstStop = rememberMainContentFocusAnchorRequester(enabled = hasStatsCard)
 * StatsCard(modifier = Modifier.focusRequester(firstStop))
 * ```
 *
 * Registration ends on ON_PAUSE/ON_STOP/ON_DESTROY so a page that is no longer on screen cannot keep the slot:
 * otherwise the bar would link DOWN into a page the user cannot see. Pass `enabled = false` while the stop is
 * not part of the layout (empty state, a collapsed section) so the slot falls back to 2D search instead of
 * being handed a requester that is attached to nothing.
 */
@Composable
fun rememberMainContentFocusAnchorRequester(enabled: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    PublishMainContentFocusAnchor(requester = requester, enabled = enabled)
    return requester
}

/**
 * Publishes an already existing [requester] into [LocalMainContentFocusAnchor], for pages whose first stop is
 * anchored by another seam and only needs to be announced.
 *
 * The favourites host is such a page: it already hands the same requester to its inline category tabs through
 * the top-bar override state, and the quick-filter row's sort control is the first stop the shell's search,
 * filter and overflow buttons should reach. Passing `null` (a host that is not a TV) publishes nothing.
 */
@Composable
fun PublishMainContentFocusAnchor(requester: FocusRequester?, enabled: Boolean = true) {
    val anchor = LocalMainContentFocusAnchor.current
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(anchor, requester, isTvPresentation, enabled, lifecycleOwner) {
        if (anchor == null || requester == null || !isTvPresentation || !enabled) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> anchor.register(requester)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    anchor.unregister(requester)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            anchor.register(requester)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            anchor.unregister(requester)
        }
    }
}
