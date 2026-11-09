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

    @Test
    fun aSourceGivenUpOnForRateLimitsCoolsDownForAtLeastHalfAnHour() {
        val health = SourceHealth()
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterRateLimit(now = 0L, retryAfterMs = null)
            .afterRateLimit(now = 1_000L, retryAfterMs = null)

        assertEquals(1_000L + SourceHealth.RATE_LIMIT_COOLDOWN_MS, health.cooldownUntil)
    }

    @Test
    fun aLongRetryAfterSetsTheCooldownUpToTheCap() {
        assertEquals(
            3_600_000L,
            SourceHealth().afterRateLimit(now = 0L, retryAfterMs = 3_600_000L).cooldownUntil,
        )
        assertEquals(
            SourceHealth.MAX_COOLDOWN_MS,
            SourceHealth().afterRateLimit(now = 0L, retryAfterMs = 7 * 24 * 3_600_000L).cooldownUntil,
        )
    }

    @Test
    fun pausesAndChallengesDoNotCoolDown() {
        assertEquals(0L, SourceHealth().afterRateLimit(now = 0L, retryAfterMs = 20_000L).cooldownUntil)
        assertEquals(0L, SourceHealth().afterChallenge().cooldownUntil)
    }

    @Test
    fun cooldownsRoundTripAndDropExpiredOrMalformedEntries() {
        val encoded = encodeSourceCooldowns(mapOf("MIHON_1" to 5_000L, "OLD" to 900L), now = 1_000L)

        assertEquals(setOf("MIHON_1=5000"), encoded)
        assertEquals(
            mapOf("MIHON_1" to 5_000L, "A=B" to 7L),
            decodeSourceCooldowns(encoded + setOf("A=B=7", "=3", "broken", "X=notanumber")),
        )
    }

    @Test
    fun aBatchLeavesOutSourcesStillCoolingDownAndKeepsTheQueueOrder() {
        val queue = listOf("a1", "a2", "b1", "a3", "c1", "b2", "c2")

        assertEquals(
            listOf("b1", "c1", "b2"),
            selectTrackBatch(
                queue = queue,
                sourceOf = { it.take(1) },
                limit = 3,
                cooldowns = mapOf("a" to 2_000L, "c" to 500L),
                challenged = emptySet(),
                now = 1_000L,
            ),
        )
    }

    @Test
    fun aChallengedSourceOnlyPutsAProbeIntoTheBatch() {
        // Source "a" holds the oldest titles; without the probe limit it would fill the whole batch again.
        val queue = listOf("a1", "a2", "a3", "a4", "b1", "b2")

        assertEquals(
            listOf("a1", "b1", "b2"),
            selectTrackBatch(
                queue = queue,
                sourceOf = { it.take(1) },
                limit = 4,
                cooldowns = emptyMap(),
                challenged = setOf("a"),
                now = 0L,
            ),
        )
    }

    @Test
    fun titlesWithoutASourceStayInTheBatch() {
        assertEquals(
            listOf("x", "b1"),
            selectTrackBatch(
                queue = listOf("x", "b1"),
                sourceOf = { if (it == "x") null else it.take(1) },
                limit = 10,
                cooldowns = mapOf("b" to 0L),
                challenged = emptySet(),
                now = 1L,
            ),
        )
        assertEquals(emptyList<String>(), selectTrackBatch(listOf("a1"), { "a" }, 0, emptyMap(), emptySet(), 0L))
    }

    @Test
    fun aSourceStaysChallengedUntilItAnswersWithoutAChallenge() {
        assertEquals(setOf("a", "b"), nextChallengedSources(setOf("a"), challenged = setOf("b"), answered = emptySet()))
        assertEquals(setOf("b"), nextChallengedSources(setOf("a", "b"), challenged = emptySet(), answered = setOf("a")))
        // One title answered while another was challenged: the source is still challenged.
        assertEquals(setOf("a"), nextChallengedSources(emptySet(), challenged = setOf("a"), answered = setOf("a")))
    }
}
