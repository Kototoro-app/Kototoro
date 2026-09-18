package org.skepsun.kototoro.reader.ui.config

import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.core.SceneReadingDirection

/**
 * Which physical direction a reading mode lays out in.
 *
 * [ReaderMode] remains a legacy preference projection that mixes layout with reading direction
 * (STANDARD / REVERSED both mean "paged"). The scene core deliberately does not inherit that
 * coupling: it takes a [SceneReadingDirection] as a parameter, and the mode-to-direction decision
 * is folded in here, in the configuration layer, so a future settings split has exactly one place
 * to change.
 *
 * @param isContinuousHorizontalReversed direction preference of the continuous-horizontal mode,
 * which is a mode of its own rather than a variant of the paged direction.
 */
internal fun resolveSceneReadingDirection(
    mode: ReaderMode,
    isContinuousHorizontalReversed: Boolean = false,
): SceneReadingDirection = when (mode) {
    ReaderMode.STANDARD -> SceneReadingDirection.LEFT_TO_RIGHT
    ReaderMode.REVERSED -> SceneReadingDirection.RIGHT_TO_LEFT
    ReaderMode.VERTICAL -> SceneReadingDirection.TOP_TO_BOTTOM
    ReaderMode.WEBTOON -> SceneReadingDirection.TOP_TO_BOTTOM
    ReaderMode.CONTINUOUS_HORIZONTAL -> if (isContinuousHorizontalReversed) {
        SceneReadingDirection.RIGHT_TO_LEFT
    } else {
        SceneReadingDirection.LEFT_TO_RIGHT
    }
}
