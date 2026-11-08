package org.skepsun.kototoro.tracker.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** What became of one item in [checkBySource]. */
sealed interface SourceCheck<out T, out R> {

    val item: T
    val source: String

    data class Checked<T, R>(override val item: T, override val source: String, val result: R) : SourceCheck<T, R>

    /** Not checked: its source was given up on earlier in the run, or is still cooling down from an earlier one. */
    data class Skipped<T>(override val item: T, override val source: String) : SourceCheck<T, Nothing>
}

/**
 * Runs [check] on every item the way [interleaveBySource] orders them, at most [sourceParallelism] at a time per
 * source and [globalParallelism] overall, and emits each outcome as it lands. [answerOf] says what a result told
 * about its source: a rate limit pauses the source (holding only that source's slots, not the global ones), and
 * a challenge or repeated refusals skip the rest of it, which is emitted without calling [check].
 *
 * A source whose entry in [cooldowns] (epoch millis) is still in the future is skipped from the start. When rate
 * limits make the run give up on a source, [onCooldown] gets the time until which later runs should skip it; it is
 * called under the run's lock, one call at a time.
 *
 * [now] is the clock in epoch millis the pauses are measured on; tests pass the virtual one.
 */
fun <T, R> checkBySource(
    items: List<T>,
    sourceOf: (T) -> String,
    now: () -> Long,
    answerOf: (R) -> SourceAnswer,
    globalParallelism: Int = TRACK_GLOBAL_PARALLELISM,
    sourceParallelism: Int = TRACK_SOURCE_PARALLELISM,
    cooldowns: Map<String, Long> = emptyMap(),
    onCooldown: (source: String, until: Long) -> Unit = { _, _ -> },
    check: suspend (T) -> R,
): Flow<SourceCheck<T, R>> = channelFlow {
    val globalLimit = Semaphore(globalParallelism)
    val sourceLimits = HashMap<String, Semaphore>()
    val startedAt = now()
    val health = HashMap<String, SourceHealth>()
    for ((source, until) in cooldowns) {
        if (until > startedAt) health[source] = SourceHealth(skipped = true, cooldownUntil = until)
    }
    val healthLock = Mutex()

    suspend fun awaitTurn(source: String): Boolean {
        while (true) {
            val time = now()
            when (val turn = healthLock.withLock { health[source] ?: SourceHealth() }.turn(time)) {
                SourceTurn.Go -> return true
                SourceTurn.Skip -> return false
                is SourceTurn.WaitUntil -> delay(turn.time - time)
            }
        }
    }

    for (item in interleaveBySource(items, sourceOf)) {
        val source = sourceOf(item)
        val sourceLimit = sourceLimits.getOrPut(source) { Semaphore(sourceParallelism) }
        launch {
            sourceLimit.withPermit {
                if (!awaitTurn(source)) {
                    send(SourceCheck.Skipped(item, source))
                    return@withPermit
                }
                val result = globalLimit.withPermit { check(item) }
                val answer = answerOf(result)
                healthLock.withLock {
                    val before = health[source] ?: SourceHealth()
                    val after = before.after(answer, now())
                    health[source] = after
                    if (after.cooldownUntil > before.cooldownUntil) onCooldown(source, after.cooldownUntil)
                }
                send(SourceCheck.Checked(item, source, result))
            }
        }
    }
}
