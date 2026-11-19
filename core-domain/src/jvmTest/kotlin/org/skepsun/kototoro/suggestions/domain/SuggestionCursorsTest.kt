package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionCursorsTest {

    @Test
    fun `cursors round trip and skip malformed entries`() {
        val cursors = mapOf("MIHON_1" to 4, "A=B" to 6)
        assertEquals(cursors, SuggestionCursors.parse(SuggestionCursors.encode(cursors)))
        assertEquals(mapOf("X" to 3), SuggestionCursors.parse(listOf("X=3", "bad", "Y=z", "=5")))
    }

    @Test
    fun `only answering sources move and new ones start after the refresh pages`() {
        val next = SuggestionCursors.advance(mapOf("A" to 4, "B" to 6), listOf("A", "C"))
        assertEquals(mapOf("A" to 6, "B" to 6, "C" to 4), next)
    }
}
