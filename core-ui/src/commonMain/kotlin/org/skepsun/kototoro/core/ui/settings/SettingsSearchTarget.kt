package org.skepsun.kototoro.core.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag

/**
 * A new id also reveals a repeated search for the same preference. [title] lets a row that was never given
 * a search key still answer to the result the user tapped.
 */
data class SettingsSearchTarget(val key: String, val requestId: Int, val title: String? = null) {
    /** Set by the row that scrolled itself into view, so a lazy page stops looking for it. */
    internal var revealed by mutableStateOf(false)
}

val LocalSettingsSearchTarget = staticCompositionLocalOf<SettingsSearchTarget?> { null }

@Composable
fun Modifier.revealSettingsSearchTarget(key: String): Modifier =
    revealSettingsSearchRow { it.key == key }.testTag("setting:$key")

/** The title match for rows without a search key; the shared preference rows apply it themselves. */
@Composable
fun Modifier.revealSettingsSearchTitle(title: String): Modifier =
    if (LocalSettingsSearchTarget.current?.title == null) this else revealSettingsSearchRow { it.title == title }

@Composable
private fun Modifier.revealSettingsSearchRow(matches: (SettingsSearchTarget) -> Boolean): Modifier {
    val target = LocalSettingsSearchTarget.current
    val requester = remember { BringIntoViewRequester() }
    val highlight = remember { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(target) {
        if (target != null && matches(target)) {
            // A disclosure may have just composed this row. Wait for its first measured frame.
            withFrameNanos { }
            target.revealed = true
            requester.bringIntoView()
            // Say which row the search meant; the page it opened may hold a dozen similar ones.
            highlight.snapTo(1f)
            highlight.animateTo(0f, tween(durationMillis = SearchHighlightFadeMillis))
        }
    }
    return bringIntoViewRequester(requester).drawBehind {
        if (highlight.value > 0f) drawRect(highlightColor.copy(alpha = SearchHighlightAlpha * highlight.value))
    }
}

/**
 * A lazy page only composes the rows near its viewport, so the searched row may not exist yet to reveal
 * itself. Walk the list forward until it does; a target this page does not hold leaves the page at its top.
 */
@Composable
fun SettingsSearchLazyReveal(listState: LazyListState) {
    val target = LocalSettingsSearchTarget.current ?: return
    LaunchedEffect(target) {
        repeat(SearchRevealSettleFrames) { withFrameNanos { } }
        while (!target.revealed && listState.canScrollForward) {
            listState.scrollBy(listState.layoutInfo.viewportSize.height * SearchSeekViewportFraction)
            repeat(SearchRevealSettleFrames) { withFrameNanos { } }
        }
        if (!target.revealed) listState.scrollToItem(0)
    }
}

private const val SearchHighlightAlpha = 0.16f
private const val SearchHighlightFadeMillis = 1600
// One frame composes the newly scrolled-in rows, the next lets their reveal effect run.
private const val SearchRevealSettleFrames = 2
private const val SearchSeekViewportFraction = 0.75f
