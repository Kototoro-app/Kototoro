package org.skepsun.kototoro.reader.ui.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.core.SceneReadingDirection

/**
 * Which physical direction a reading mode lays out in.
 *
 * `ReaderMode` is a legacy preference projection that still mixes layout with direction, so the
 * continuous-horizontal direction lives in its own preference and is folded in here, in the
 * configuration layer, instead of leaking into the scene core.
 */
class SceneReadingDirectionResolverTest {

    @Test
    fun `paged modes keep their own reading direction regardless of the continuous preference`() {
        assertEquals(
            SceneReadingDirection.LEFT_TO_RIGHT,
            resolveSceneReadingDirection(ReaderMode.STANDARD, isContinuousHorizontalReversed = false),
        )
        assertEquals(
            SceneReadingDirection.LEFT_TO_RIGHT,
            resolveSceneReadingDirection(ReaderMode.STANDARD, isContinuousHorizontalReversed = true),
        )
        assertEquals(
            SceneReadingDirection.RIGHT_TO_LEFT,
            resolveSceneReadingDirection(ReaderMode.REVERSED, isContinuousHorizontalReversed = false),
        )
        assertEquals(
            SceneReadingDirection.RIGHT_TO_LEFT,
            resolveSceneReadingDirection(ReaderMode.REVERSED, isContinuousHorizontalReversed = true),
        )
        assertEquals(
            SceneReadingDirection.TOP_TO_BOTTOM,
            resolveSceneReadingDirection(ReaderMode.VERTICAL, isContinuousHorizontalReversed = true),
        )
    }

    @Test
    fun `webtoon reads top to bottom`() {
        assertEquals(
            SceneReadingDirection.TOP_TO_BOTTOM,
            resolveSceneReadingDirection(ReaderMode.WEBTOON, isContinuousHorizontalReversed = false),
        )
    }

    @Test
    fun `continuous horizontal follows its own direction preference`() {
        assertEquals(
            SceneReadingDirection.LEFT_TO_RIGHT,
            resolveSceneReadingDirection(
                ReaderMode.CONTINUOUS_HORIZONTAL,
                isContinuousHorizontalReversed = false,
            ),
        )
        assertEquals(
            SceneReadingDirection.RIGHT_TO_LEFT,
            resolveSceneReadingDirection(
                ReaderMode.CONTINUOUS_HORIZONTAL,
                isContinuousHorizontalReversed = true,
            ),
        )
    }

    @Test
    fun `every mode resolves a direction without depending on the scene engine`() {
        // The resolver must stay total: an unhandled mode would silently fall back to a physical
        // direction that contradicts the mode the reader picked.
        val resolved = ReaderMode.entries.associateWith { mode ->
            resolveSceneReadingDirection(mode, isContinuousHorizontalReversed = false)
        }

        assertEquals(ReaderMode.entries.size, resolved.size)
        assertEquals(SceneReadingDirection.LEFT_TO_RIGHT, resolved.getValue(ReaderMode.STANDARD))
        assertEquals(SceneReadingDirection.RIGHT_TO_LEFT, resolved.getValue(ReaderMode.REVERSED))
        assertEquals(SceneReadingDirection.TOP_TO_BOTTOM, resolved.getValue(ReaderMode.VERTICAL))
        assertEquals(SceneReadingDirection.TOP_TO_BOTTOM, resolved.getValue(ReaderMode.WEBTOON))
        assertEquals(SceneReadingDirection.LEFT_TO_RIGHT, resolved.getValue(ReaderMode.CONTINUOUS_HORIZONTAL))
    }
}
