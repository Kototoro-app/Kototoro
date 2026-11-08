package org.skepsun.kototoro.tracker.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceCheckRunTest {

    /** "a1" is the first title of source "a". */
    private fun sourceOf(item: String) = item.take(1)

    @Test
    fun noSourceRunsMoreChecksAtOnceThanItsLimit() = runTest {
        val running = HashMap<String, Int>()
        val peakPerSource = HashMap<String, Int>()
        var runningOverall = 0
        var peakOverall = 0
        val items = (1..10).map { "a$it" } + (1..10).map { "b$it" } + (1..10).map { "c$it" }

        val results = checkBySource(
            items = items,
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime },
            answerOf = { SourceAnswer.Answered },
            globalParallelism = 5,
            sourceParallelism = 2,
        ) { item ->
            val source = sourceOf(item)
            running[source] = (running[source] ?: 0) + 1
            peakPerSource[source] = maxOf(peakPerSource[source] ?: 0, running.getValue(source))
            peakOverall = maxOf(peakOverall, ++runningOverall)
            delay(100)
            running[source] = running.getValue(source) - 1
            runningOverall--
            item
        }.toList()

        assertEquals(30, results.size)
        assertTrue(results.all { it is SourceCheck.Checked })
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 2), peakPerSource)
        assertEquals(5, peakOverall)
    }

    @Test
    fun aSingleSourceIsNotStarvedByABigOne() = runTest {
        val started = ArrayList<String>()
        val items = (1..20).map { "a$it" } + "b1"

        checkBySource(
            items = items,
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime },
            answerOf = { SourceAnswer.Answered },
            globalParallelism = 3,
            sourceParallelism = 3,
        ) { item ->
            started += item
            delay(100)
        }.toList()

        // With the source limit as wide as the global one, only taking sources in turn lets the lone title of
        // "b" start second instead of after the first wave of "a".
        assertEquals(1, started.indexOf("b1"))
    }

    @Test
    fun aRateLimitPausesOnlyThatSource() = runTest {
        val startedAt = HashMap<String, Long>()

        checkBySource(
            items = listOf("a1", "a2", "b1", "b2"),
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime },
            answerOf = { item: String -> if (item == "a1") SourceAnswer.RateLimited(30_000L) else SourceAnswer.Answered },
            globalParallelism = 4,
            sourceParallelism = 1,
        ) { item ->
            startedAt[item] = testScheduler.currentTime
            delay(100)
            item
        }.toList()

        assertEquals(0L, startedAt["a1"])
        // a2 waits out the 30 s from when a1 answered; b keeps going meanwhile.
        assertEquals(30_100L, startedAt["a2"])
        assertEquals(100L, startedAt["b2"])
    }

    @Test
    fun aChallengeSkipsTheRestOfThatSourceWithoutCheckingIt() = runTest {
        val checked = ArrayList<String>()

        val results = checkBySource(
            items = listOf("a1", "a2", "a3", "b1", "b2"),
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime },
            answerOf = { item: String -> if (item == "a1") SourceAnswer.Challenged else SourceAnswer.Answered },
            sourceParallelism = 1,
        ) { item ->
            checked += item
            delay(100)
            item
        }.toList()

        assertEquals(listOf("a1", "b1", "b2"), checked.sorted())
        assertEquals(
            setOf("a2", "a3"),
            results.filterIsInstance<SourceCheck.Skipped<String>>().map { it.item }.toSet(),
        )
        assertEquals(5, results.size)
    }

    @Test
    fun aSourceStillCoolingDownIsSkippedAndAnExpiredOneIsChecked() = runTest {
        val checked = ArrayList<String>()

        val results = checkBySource(
            items = listOf("a1", "a2", "b1", "c1"),
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime + 10_000L },
            answerOf = { _: String -> SourceAnswer.Answered },
            cooldowns = mapOf("a" to 20_000L, "b" to 10_000L),
        ) { item ->
            checked += item
            item
        }.toList()

        assertEquals(listOf("b1", "c1"), checked.sorted())
        assertEquals(
            setOf("a1", "a2"),
            results.filterIsInstance<SourceCheck.Skipped<String>>().map { it.item }.toSet(),
        )
    }

    @Test
    fun givingUpOnARateLimitingSourceReportsItsCooldownOnce() = runTest {
        val reported = ArrayList<Pair<String, Long>>()

        val results = checkBySource(
            items = listOf("a1", "a2", "a3", "b1"),
            sourceOf = ::sourceOf,
            now = { testScheduler.currentTime },
            answerOf = { item: String ->
                if (item.startsWith("a")) SourceAnswer.RateLimited(2 * 3_600_000L) else SourceAnswer.Answered
            },
            sourceParallelism = 1,
            onCooldown = { source, until -> reported += source to until },
        ) { item ->
            delay(100)
            item
        }.toList()

        assertEquals(listOf("a" to 100L + 2 * 3_600_000L), reported)
        assertEquals(
            setOf("a2", "a3"),
            results.filterIsInstance<SourceCheck.Skipped<String>>().map { it.item }.toSet(),
        )
    }
}
