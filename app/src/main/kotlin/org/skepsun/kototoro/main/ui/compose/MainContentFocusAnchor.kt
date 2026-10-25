package org.skepsun.kototoro.main.ui.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

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
