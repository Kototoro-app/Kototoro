package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import org.skepsun.kototoro.R

/**
 * Stable viewport semantics shared by the three Scene reader hosts (improvement plan
 * 2026-09 §5.2, closure plan CS-8).
 *
 * Design rules (per plan):
 * - The node describes SETTLED reading progress — page number(s) over total — never the
 *   per-frame viewport offset, so TalkBack does not re-announce on every animation frame.
 * - Page identity is expressed through semantic properties; the test tag itself is stable
 *   and never changes per frame or per page.
 * - Previous/next page (and chapter, where the host supports it) are custom actions that
 *   reuse the host's existing navigation entry points — no parallel accessibility-only
 *   page-turn logic. Actions the first/last page cannot perform are simply not offered.
 * - The node does not merge descendants, so independent controls (error retry, pull
 *   feedback) keep their own accessibility nodes.
 */
internal object SceneReaderViewportSemantics {
    const val PAGED_VIEWPORT_TEST_TAG = "kototoro.reader.scene.pagedViewport"
    const val WEBTOON_VIEWPORT_TEST_TAG = "kototoro.reader.scene.webtoonViewport"
    const val HORIZONTAL_VIEWPORT_TEST_TAG = "kototoro.reader.scene.horizontalViewport"
}

/**
 * Settled page window in reading order used for the viewport's announced position.
 *
 * @param lowerIndex 0-based index of the first settled page.
 * @param upperIndex 0-based index of the last settled page (`== lowerIndex` for discrete
 *   single-page hosts; continuous hosts may settle across several pages).
 */
internal data class SceneSettledPages(
    val lowerIndex: Int,
    val upperIndex: Int,
) {
    val isSpread: Boolean get() = upperIndex > lowerIndex
}

/** Availability + handlers for the viewport's custom accessibility actions. */
internal class SceneReaderViewportActions(
    val canPreviousPage: Boolean,
    val canNextPage: Boolean,
    val onPreviousPage: () -> Unit,
    val onNextPage: () -> Unit,
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
)

/**
 * Applies the stable viewport semantics: test tag, settled page-position description and
 * the custom actions built from [actions].
 */
@Composable
internal fun Modifier.sceneReaderViewportSemantics(
    testTag: String,
    totalPages: Int,
    settled: SceneSettledPages?,
    actions: SceneReaderViewportActions,
): Modifier {
    val positionDescription = settled?.let {
        if (it.isSpread) {
            stringResource(R.string.reader_a11y_page_position_spread, it.lowerIndex + 1, it.upperIndex + 1, totalPages)
        } else {
            stringResource(R.string.reader_a11y_page_position, it.lowerIndex + 1, totalPages)
        }
    }
    val previousPageLabel = stringResource(R.string.reader_a11y_previous_page)
    val nextPageLabel = stringResource(R.string.reader_a11y_next_page)
    val previousChapterLabel = stringResource(R.string.reader_a11y_previous_chapter)
    val nextChapterLabel = stringResource(R.string.reader_a11y_next_chapter)

    return this
        .testTag(testTag)
        .semantics {
            if (positionDescription != null) {
                contentDescription = positionDescription
            }
            customActions = buildList {
                if (actions.canPreviousPage) {
                    add(CustomAccessibilityAction(previousPageLabel) { actions.onPreviousPage(); true })
                }
                if (actions.canNextPage) {
                    add(CustomAccessibilityAction(nextPageLabel) { actions.onNextPage(); true })
                }
                if (actions.canPreviousChapter) {
                    add(CustomAccessibilityAction(previousChapterLabel) { actions.onPreviousChapter(); true })
                }
                if (actions.canNextChapter) {
                    add(CustomAccessibilityAction(nextChapterLabel) { actions.onNextChapter(); true })
                }
            }
        }
}
