package org.skepsun.kototoro.tracker.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackSourceRulesTest {

    @Test
    fun interleavingTakesSourcesInTurnAndKeepsEachSourcesOrder() {
        val items = listOf("a1", "a2", "a3", "a4", "b1", "c1", "b2")

        assertEquals(
            listOf("a1", "b1", "c1", "a2", "b2", "a3", "a4"),
            interleaveBySource(items) { it.first() },
        )
    }

    @Test
    fun interleavingAnEmptyListGivesAnEmptyList() {
        assertEquals(emptyList<String>(), interleaveBySource(emptyList<String>()) { it })
    }

    @Test
    fun aHealthySourceGoes() {
        assertEquals(SourceTurn.Go, SourceHealth().turn(now = 1_000L))
    }

    @Test
    fun aRateLimitHonoursRetryAfter() {
        val health = SourceHealth().afterRateLimit(now = 1_000L, retryAfterMs = 20_000L)

        assertEquals(SourceTurn.WaitUntil(21_000L), health.turn(now = 1_000L))
        assertEquals(SourceTurn.Go, health.turn(now = 21_000L))
    }

    @Test
    fun aRateLimitWithoutRetryAfterBacksOffExponentially() {
        val first = SourceHealth().afterRateLimit(now = 0L, retryAfterMs = null)
        val second = first.afterRateLimit(now = 0L, retryAfterMs = -1L)

        assertEquals(SourceTurn.WaitUntil(5_000L), first.turn(now = 0L))
        assertEquals(SourceTurn.WaitUntil(10_000L), second.turn(now = 0L))
    }

    @Test
    fun aSourceThatKeepsRefusingIsSkipped() {
        val health = SourceHealth()
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterRateLimit(now = 0L, retryAfterMs = null)

        assertEquals(SourceTurn.Skip, health.turn(now = 0L))
    }

    @Test
    fun anAnswerInBetweenResetsTheRefusals() {
        val health = SourceHealth()
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterAnswer()
            .afterRateLimit(now = 100_000L, retryAfterMs = null)

        assertEquals(SourceTurn.WaitUntil(105_000L), health.turn(now = 100_000L))
    }

    @Test
    fun aLongRetryAfterSkipsTheSource() {
        val health = SourceHealth().afterRateLimit(now = 0L, retryAfterMs = 3_600_000L)

        assertEquals(SourceTurn.Skip, health.turn(now = 0L))
    }

    @Test
    fun aChallengeSkipsTheSource() {
        assertEquals(SourceTurn.Skip, SourceHealth().afterChallenge().turn(now = 0L))
    }
}
