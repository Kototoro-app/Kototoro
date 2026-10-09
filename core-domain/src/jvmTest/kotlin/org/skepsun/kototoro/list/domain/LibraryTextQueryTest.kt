package org.skepsun.kototoro.list.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryTextQueryTest {

    @Test
    fun `blank input matches everything`() {
        val query = LibraryTextQuery("  \t ")

        assertTrue(query.isEmpty)
        assertTrue(query.matches(sequenceOf(null)))
    }

    @Test
    fun `every term must appear in some field, ignoring case`() {
        val fields = sequenceOf("One Piece", null, "Eiichiro Oda")

        assertTrue(LibraryTextQuery("piece ODA").matches(fields))
        assertTrue(LibraryTextQuery("  one   piece ").matches(fields))
        assertFalse(LibraryTextQuery("piece naruto").matches(fields))
    }

    @Test
    fun `non latin text matches by substring`() {
        assertTrue(LibraryTextQuery("海贼").matches(sequenceOf("航海王", "海贼王")))
        assertFalse(LibraryTextQuery("火影").matches(sequenceOf("航海王", "海贼王")))
    }
}
