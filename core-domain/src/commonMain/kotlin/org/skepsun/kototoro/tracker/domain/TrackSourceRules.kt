package org.skepsun.kototoro.tracker.domain

/*
 * How one check run spreads its requests over sources. Most libraries sit on a few sources, so a plain global
 * limit can put every request on one site; these rules interleave sources, cap each one, and back a source off
 * for the rest of the run when it answers with a rate limit or a challenge. A source given up on because of rate
 * limits also gets a cooldown that the next runs respect, so it is not asked again as soon as the next run starts.
 */

/** Checks running at once in one run. */
const val TRACK_GLOBAL_PARALLELISM = 8

/** Checks running at once against one source. */
const val TRACK_SOURCE_PARALLELISM = 2

/**
 * [items] taken round-robin by source: the first of every source, then the second of every source, and so on,
 * sources in the order they first appear. The order within each source is kept, so an oldest-first list stays
 * oldest-first per source and roughly so overall.
 */
fun <T, K> interleaveBySource(items: List<T>, sourceOf: (T) -> K): List<T> {
    val queues = LinkedHashMap<K, ArrayDeque<T>>()
    for (item in items) {
        queues.getOrPut(sourceOf(item)) { ArrayDeque() }.addLast(item)
    }
    val result = ArrayList<T>(items.size)
    while (result.size < items.size) {
        for (queue in queues.values) {
            queue.removeFirstOrNull()?.let(result::add)
        }
    }
    return result
}

/** Titles a source that challenged the last run may put into the next one, to find out whether it answers again. */
const val CHALLENGED_SOURCE_PROBES = 1

/**
 * The titles a run checks, out of [queue] (least recently checked first). Skipped titles are not marked as
 * checked, so a source that keeps being skipped would otherwise keep its titles at the head of the queue and fill
 * every run with titles that are skipped again: sources still in [cooldowns] (epoch millis, compared with [now])
 * are left out, and a source in [challenged] puts in only [probes] titles until it answers. Titles that are not
 * [due] yet (see [isTrackDue]) are left out too. The order of the queue is kept.
 */
fun <T> selectTrackBatch(
    queue: List<T>,
    sourceOf: (T) -> String?,
    limit: Int,
    cooldowns: Map<String, Long>,
    challenged: Set<String>,
    now: Long,
    probes: Int = CHALLENGED_SOURCE_PROBES,
    due: (T) -> Boolean = { true },
): List<T> {
    if (limit <= 0) return emptyList()
    val taken = HashMap<String, Int>()
    val result = ArrayList<T>(minOf(limit, queue.size))
    for (item in queue) {
        if (!due(item)) continue
        val source = sourceOf(item)
        if (source != null) {
            if ((cooldowns[source] ?: 0L) > now) continue
            if (source in challenged) {
                val count = taken[source] ?: 0
                if (count >= probes) continue
                taken[source] = count + 1
            }
        }
        result += item
        if (result.size >= limit) break
    }
    return result
}

/**
 * The challenged sources after a run: those that challenged it, plus those that challenged before and were not
 * heard from, minus those that answered.
 */
fun nextChallengedSources(previous: Set<String>, challenged: Set<String>, answered: Set<String>): Set<String> =
    (previous + challenged) - (answered - challenged)

/** What a source may do next in this run. */
sealed interface SourceTurn {

    data object Go : SourceTurn

    /** Rate limited: wait until [time] (epoch millis) before the next request. */
    data class WaitUntil(val time: Long) : SourceTurn

    /** Given up on for this run; its titles keep their place for the next one. */
    data object Skip : SourceTurn
}

/** What one check told about its source. */
sealed interface SourceAnswer {

    /** The source answered, successfully or not, without pushing back. */
    data object Answered : SourceAnswer

    /** HTTP 429; [retryAfterMs] when the source said how long to wait. */
    data class RateLimited(val retryAfterMs: Long?) : SourceAnswer

    /** A Cloudflare or similar challenge that a background check cannot solve. */
    data object Challenged : SourceAnswer
}

/**
 * One source's state during a check run. [cooldownUntil] (epoch millis) is set when rate limits made the run give
 * up on the source: until then later runs skip it too.
 */
data class SourceHealth(
    val pausedUntil: Long = 0L,
    val rateLimits: Int = 0,
    val skipped: Boolean = false,
    val cooldownUntil: Long = 0L,
) {

    fun turn(now: Long): SourceTurn = when {
        skipped -> SourceTurn.Skip
        pausedUntil > now -> SourceTurn.WaitUntil(pausedUntil)
        else -> SourceTurn.Go
    }

    /**
     * After a 429. Honours [retryAfterMs] when the source sent one, else backs off exponentially. A source that
     * keeps refusing, or asks for a long wait, is skipped: holding a run open for it helps no other source. It
     * then cools down for at least [RATE_LIMIT_COOLDOWN_MS], longer when the source asked for it, up to
     * [MAX_COOLDOWN_MS].
     */
    fun afterRateLimit(now: Long, retryAfterMs: Long?): SourceHealth {
        val count = rateLimits + 1
        val pause = retryAfterMs?.takeIf { it > 0 }
            ?: (RATE_LIMIT_BACKOFF_MS shl (count - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
        if (count >= MAX_RATE_LIMITS || pause > MAX_PAUSE_MS) {
            val cooldown = pause.coerceIn(RATE_LIMIT_COOLDOWN_MS, MAX_COOLDOWN_MS)
            return copy(rateLimits = count, skipped = true, cooldownUntil = maxOf(cooldownUntil, now + cooldown))
        }
        return copy(pausedUntil = maxOf(pausedUntil, now + pause), rateLimits = count)
    }

    /**
     * After a Cloudflare or similar challenge: background checks cannot solve it, so the rest would fail too. No
     * cooldown: the next run spends one request to find out whether the user has passed the check meanwhile.
     */
    fun afterChallenge(): SourceHealth = copy(skipped = true)

    /** After an answer that was not a rate limit: the source is keeping up again. */
    fun afterAnswer(): SourceHealth = if (rateLimits == 0) this else copy(rateLimits = 0)

    fun after(answer: SourceAnswer, now: Long): SourceHealth = when (answer) {
        SourceAnswer.Answered -> afterAnswer()
        is SourceAnswer.RateLimited -> afterRateLimit(now, answer.retryAfterMs)
        SourceAnswer.Challenged -> afterChallenge()
    }

    companion object {

        /** Shortest cooldown after a run gave up on a rate-limiting source. */
        const val RATE_LIMIT_COOLDOWN_MS = 30 * 60_000L

        /** Longest cooldown, whatever Retry-After asked for. */
        const val MAX_COOLDOWN_MS = 6 * 60 * 60_000L

        private const val RATE_LIMIT_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_SHIFT = 4
        private const val MAX_PAUSE_MS = 60_000L
        private const val MAX_RATE_LIMITS = 3
    }
}

/**
 * Source cooldowns as stored between runs: one "<source>=<epoch millis>" string each, the format Android keeps in
 * preferences. Malformed entries are dropped.
 */
fun decodeSourceCooldowns(entries: Set<String>): Map<String, Long> = entries.mapNotNull { entry ->
    val separator = entry.lastIndexOf('=')
    if (separator <= 0) return@mapNotNull null
    entry.substring(separator + 1).toLongOrNull()?.let { until -> entry.substring(0, separator) to until }
}.toMap()

/** [cooldowns] still running at [now], encoded for storage; expired ones are left out. */
fun encodeSourceCooldowns(cooldowns: Map<String, Long>, now: Long): Set<String> =
    cooldowns.filterValues { it > now }.mapTo(LinkedHashSet()) { (source, until) -> "$source=$until" }
