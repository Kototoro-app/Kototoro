package org.skepsun.kototoro.extensions.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceMigrationPreselectionTest {

    private val worksBySource = mapOf(
        "TSUNDOKU_9001" to listOf(11L, 12L, 13L),
        "MIHON_42" to listOf(21L, 22L),
    )

    @Test
    fun `returns all works for the requested source`() {
        val result = SourceMigrationPreselection.preselectAffectedWorks("TSUNDOKU_9001", worksBySource)

        assertEquals(listOf(11L, 12L, 13L), result)
    }

    @Test
    fun `returns empty list for an unknown source key`() {
        val result = SourceMigrationPreselection.preselectAffectedWorks("MIHON_999", worksBySource)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list for an empty works map`() {
        val result = SourceMigrationPreselection.preselectAffectedWorks("TSUNDOKU_9001", emptyMap())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deduplicates repeated ids from a single source`() {
        val duplicates = mapOf("MIHON_1" to listOf(1L, 2L, 1L, 3L, 2L))

        val result = SourceMigrationPreselection.preselectAffectedWorks("MIHON_1", duplicates)

        assertEquals(listOf(1L, 2L, 3L), result)
    }

    @Test
    fun `set overload matches the migration panel seed shape`() {
        val result = SourceMigrationPreselection.preselectAffectedWorksAsSet("MIHON_42", worksBySource)

        assertEquals(setOf(21L, 22L), result)
    }
}
