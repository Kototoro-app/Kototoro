package org.skepsun.kototoro.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManualOrderTest {

    @Test
    fun `preserves original when requested order is empty`() {
        val current = listOf("a", "b", "c", "d")
        val result = mergeManualOrder(current, emptyList())
        assertEquals(current, result)
    }

    @Test
    fun `reorders specified items while preserving positions of unmentioned items`() {
        val current = listOf("a", "b", "c", "d", "e")
        // Request reordering only b, d, a to d, a, b; c and e should remain in their slots
        val result = mergeManualOrder(current, listOf("d", "a", "b"))
        assertEquals(listOf("d", "a", "c", "b", "e"), result)
    }

    @Test
    fun `ignores items in requested that are not in current`() {
        val current = listOf("a", "b", "c")
        val result = mergeManualOrder(current, listOf("c", "x", "y", "a"))
        assertEquals(listOf("c", "b", "a"), result)
    }

    @Test
    fun `deduplicates items in requested`() {
        val current = listOf("a", "b", "c")
        val result = mergeManualOrder(current, listOf("c", "c", "b", "a"))
        assertEquals(listOf("c", "b", "a"), result)
    }
}
