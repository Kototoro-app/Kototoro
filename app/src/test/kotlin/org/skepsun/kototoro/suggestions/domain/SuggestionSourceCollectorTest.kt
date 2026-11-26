package org.skepsun.kototoro.suggestions.domain

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionSourceCollectorTest {

    @Test
    fun `keeps fast source results when another source times out`() = runTest {
        val result = collectSourceResults(
            sources = listOf("fast", "slow"),
            maxParallelism = 2,
            sourceTimeoutMillis = 1_000,
            globalTimeoutMillis = 5_000,
        ) { source ->
            if (source == "slow") {
                delay(2_000)
            }
            listOf(source)
        }

        assertEquals(listOf("fast"), result)
    }

    @Test
    fun `keeps partial results when the global deadline expires`() = runTest {
        val result = collectSourceResults(
            sources = listOf("fast", "medium", "slow"),
            maxParallelism = 3,
            sourceTimeoutMillis = 5_000,
            globalTimeoutMillis = 1_000,
        ) { source ->
            when (source) {
                "fast" -> delay(100)
                "medium" -> delay(800)
                else -> delay(5_000)
            }
            listOf(source)
        }

        assertEquals(listOf("fast", "medium"), result)
    }

    @Test
    fun `keeps every source that answers however many answered before it`() = runTest {
        val result = collectSourceResults(
            sources = listOf("a", "b", "c"),
            maxParallelism = 3,
            sourceTimeoutMillis = 5_000,
            globalTimeoutMillis = 5_000,
        ) { source -> List(300) { "$source$it" } }

        assertEquals(900, result.size)
        assertEquals(setOf("a", "b", "c"), result.mapTo(HashSet()) { it.take(1) })
    }

    @Test
    fun `propagates parent cancellation to source fetches`() = runTest {
        var cancelled = false
        val job = launch {
            collectSourceResults<String, Int>(
                sources = listOf("slow"),
                sourceTimeoutMillis = 5_000,
                globalTimeoutMillis = 5_000,
            ) { _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
                emptyList<Int>()
            }
        }

        runCurrent()
        job.cancel()
        job.join()

        assertTrue(cancelled)
    }

    @Test
    fun `isolates ordinary source failures`() = runTest {
        val result = collectSourceResults(
            sources = listOf("good", "bad"),
            maxParallelism = 2,
        ) { source ->
            check(source == "good") { "source failed" }
            listOf(source)
        }

        assertEquals(listOf("good"), result)
    }
}
