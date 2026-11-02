package org.skepsun.kototoro.reader.ui.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderMode

/**
 * Which layout the reader composes for a saved mode.
 *
 * A television viewport cannot honour the continuous-horizontal filmstrip (it fits pages to the
 * viewport height, so tall pages become slivers), so the presentation remaps that one mode to the
 * vertical column. Everything else - including every mode on a phone - must stay untouched.
 */
class SceneLayoutModeTest {

    @Test
    fun `a phone keeps the continuous horizontal filmstrip`() {
        assertEquals(
            ReaderMode.CONTINUOUS_HORIZONTAL,
            resolveSceneLayoutMode(ReaderMode.CONTINUOUS_HORIZONTAL, isTv = false),
        )
    }

    @Test
    fun `a television renders continuous horizontal as the vertical column`() {
        assertEquals(
            ReaderMode.WEBTOON,
            resolveSceneLayoutMode(ReaderMode.CONTINUOUS_HORIZONTAL, isTv = true),
        )
    }

    @Test
    fun `webtoon is already the vertical column on both presentations`() {
        assertEquals(ReaderMode.WEBTOON, resolveSceneLayoutMode(ReaderMode.WEBTOON, isTv = false))
        assertEquals(ReaderMode.WEBTOON, resolveSceneLayoutMode(ReaderMode.WEBTOON, isTv = true))
    }

    @Test
    fun `paged modes keep their fit semantics on a television`() {
        val pagedModes = listOf(ReaderMode.STANDARD, ReaderMode.REVERSED, ReaderMode.VERTICAL)
        val resolvedOnTv = pagedModes.associateWith { resolveSceneLayoutMode(it, isTv = true) }

        assertEquals(pagedModes.size, resolvedOnTv.size)
        pagedModes.forEach { mode ->
            assertEquals(mode, resolvedOnTv.getValue(mode))
        }
    }

    @Test
    fun `an unresolved mode stays unresolved`() {
        assertEquals(null, resolveSceneLayoutMode(null, isTv = false))
        assertEquals(null, resolveSceneLayoutMode(null, isTv = true))
    }
}
