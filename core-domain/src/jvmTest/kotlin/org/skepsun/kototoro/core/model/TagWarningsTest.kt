package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagWarningsTest {

    @Test
    fun `the shared list carries every title Android used to read from its raw resource`() {
        // Android's res/raw/tags_warnlist had 110 lines; the shared constant must not shrink the set.
        assertEquals(110, TagWarnings.titles.size)
        assertTrue("rape" in TagWarnings.titles)
        // The list also matches the Russian aliases sources publish.
        assertTrue("гуро" in TagWarnings.titles)
    }

    @Test
    fun `matching is exact and case-insensitive rather than fuzzy`() {
        assertTrue(TagWarnings.isWarned("Rape"))
        assertTrue(TagWarnings.isWarned("  BODY HORROR "))
        assertTrue(TagWarnings.isWarned("Гуро"))
        // Android compared whole titles, so a title that merely contains a warn word stays plain.
        assertFalse(TagWarnings.isWarned("Drama"))
        assertFalse(TagWarnings.isWarned("rape fantasy"))
        assertFalse(TagWarnings.isWarned(""))
    }
}
