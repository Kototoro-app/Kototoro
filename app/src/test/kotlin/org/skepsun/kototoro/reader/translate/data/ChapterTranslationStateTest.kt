package org.skepsun.kototoro.reader.translate.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chapter list draws one of three things per row: a finished badge, a re-translate hint, or a
 * determinate progress ring. Those come straight from [ChapterTranslationState], so its classification has
 * to be exact: a finished chapter must never look partially translated, and an interrupted job must not
 * look finished.
 */
class ChapterTranslationStateTest {

    @Test
    fun `finished chapter is neither partial nor stale`() {
        val state = ChapterTranslationState(isPrepared = true, isStale = false, pagesDone = 12, pagesTotal = 12)

        assertFalse(state.isPartial)
        assertEquals(1f, state.progress)
    }

    @Test
    fun `finished chapter produced by other settings is stale but still finished`() {
        val state = ChapterTranslationState(isPrepared = true, isStale = true, pagesDone = 5, pagesTotal = 5)

        assertFalse(state.isPartial)
        assertTrue(state.isStale)
    }

    @Test
    fun `started job reports partial progress`() {
        val state = ChapterTranslationState(isPrepared = false, isStale = false, pagesDone = 3, pagesTotal = 12)

        assertTrue(state.isPartial)
        assertEquals(0.25f, state.progress)
    }

    @Test
    fun `chapter that only exists in the queue has no progress`() {
        val state = ChapterTranslationState(isPrepared = false, isStale = false, pagesDone = 0, pagesTotal = 0)

        assertFalse(state.isPartial)
        assertNull(state.progress)
    }

    @Test
    fun `pages done never exceeds the page count`() {
        val state = ChapterTranslationState(isPrepared = false, isStale = false, pagesDone = 20, pagesTotal = 10)

        assertEquals(1f, state.progress)
    }

    @Test
    fun `translated chapters containing only a partial entry is not empty`() {
        val translated = TranslatedChapters(ready = emptySet(), stale = emptySet(), partial = mapOf(1L to 0.5f))

        // The chapter list takes the "nothing translated" shortcut only when everything is empty.
        assertFalse(translated.isEmpty)
    }
}
