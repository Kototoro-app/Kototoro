package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EntityOrganizeRouteTest {

    @Test
    fun `encode and parse preserves sorted unique ids`() {
        val encoded = encodeEntityOrganizeSelection(setOf(9L, 3L, 3L, 12L))

        assertEquals("3,9,12", encoded)
        assertEquals(setOf(3L, 9L, 12L), parseEntityOrganizeSelection(encoded))
    }

    @Test
    fun `parse ignores blanks and invalid tokens`() {
        val parsed = parseEntityOrganizeSelection(" 7, ,bad,11,,14x,15 ")

        assertEquals(setOf(7L, 11L, 15L), parsed)
    }
}
