package org.skepsun.kototoro.tracker.domain

/*
 * How one check run spreads its requests over sources. Most libraries sit on a few sources, so a plain global
 * limit can put every request on one site; these rules interleave sources, cap each one, and back a source off
 * for the rest of the run when it answers with a rate limit or a challenge.
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

/** One source's state during a check run. */
data class SourceHealth(
    val pausedUntil: Long = 0L,
    val rateLimits: Int = 0,
    val skipped: Boolean = false,
) {

    fun turn(now: Long): SourceTurn = when {
        skipped -> SourceTurn.Skip
        pausedUntil > now -> SourceTurn.WaitUntil(pausedUntil)
        else -> SourceTurn.Go
    }

    /**
     * After a 429. Honours [retryAfterMs] when the source sent one, else backs off exponentially. A source that
     * keeps refusing, or asks for a long wait, is skipped: holding a run open for it helps no other source.
     */
    fun afterRateLimit(now: Long, retryAfterMs: Long?): SourceHealth {
        val count = rateLimits + 1
        val pause = retryAfterMs?.takeIf { it > 0 }
            ?: (RATE_LIMIT_BACKOFF_MS shl (count - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
        if (count >= MAX_RATE_LIMITS || pause > MAX_PAUSE_MS) {
            return copy(rateLimits = count, skipped = true)
        }
        return copy(pausedUntil = maxOf(pausedUntil, now + pause), rateLimits = count)
    }

    /** After a Cloudflare or similar challenge: background checks cannot solve it, so the rest would fail too. */
    fun afterChallenge(): SourceHealth = copy(skipped = true)

    /** After an answer that was not a rate limit: the source is keeping up again. */
    fun afterAnswer(): SourceHealth = if (rateLimits == 0) this else copy(rateLimits = 0)

    fun after(answer: SourceAnswer, now: Long): SourceHealth = when (answer) {
        SourceAnswer.Answered -> afterAnswer()
        is SourceAnswer.RateLimited -> afterRateLimit(now, answer.retryAfterMs)
        SourceAnswer.Challenged -> afterChallenge()
    }

    private companion object {
        const val RATE_LIMIT_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_SHIFT = 4
        const val MAX_PAUSE_MS = 60_000L
        const val MAX_RATE_LIMITS = 3
    }
}
