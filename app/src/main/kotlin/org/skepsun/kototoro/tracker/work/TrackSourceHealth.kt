package org.skepsun.kototoro.tracker.work

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.HttpException
import org.jsoup.HttpStatusException
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.tracker.domain.SourceAnswer
import org.skepsun.kototoro.tracker.domain.decodeSourceCooldowns
import org.skepsun.kototoro.tracker.domain.encodeSourceCooldowns
import org.skepsun.kototoro.tracker.domain.nextChallengedSources
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions as LegacyTooManyRequestExceptions

/**
 * What a check that failed with this error, or answered when it is null, told about its source. Sources report a
 * rate limit in their own way: the app's interceptor and Kotatsu parsers raise TooManyRequestExceptions with the
 * Retry-After delay, Mihon extensions an HttpException, Jsoup an HttpStatusException; all may come wrapped.
 */
internal fun Throwable?.toSourceAnswer(): SourceAnswer {
    val causes = generateSequence(this) { e -> e.cause?.takeIf { it !== e } }
    for (e in causes) {
        when (e) {
            is CloudFlareException, is InteractiveActionRequiredException -> return SourceAnswer.Challenged
            is TooManyRequestExceptions -> return SourceAnswer.RateLimited(e.getRetryDelay().takeIf { it > 0 })
            is LegacyTooManyRequestExceptions -> return SourceAnswer.RateLimited(e.getRetryDelay().takeIf { it > 0 })
            is HttpException -> if (e.code == HTTP_TOO_MANY_REQUESTS) return SourceAnswer.RateLimited(null)
            is HttpStatusException -> if (e.statusCode == HTTP_TOO_MANY_REQUESTS) return SourceAnswer.RateLimited(null)
        }
    }
    return SourceAnswer.Answered
}

/**
 * What earlier check runs learned about sources: those that rate-limited a run and should be left alone until
 * their time is up, and those that answered with a challenge and only get a probe until they answer again. Kept
 * in a preferences file of their own: it is per-device state, not a setting to back up or sync.
 */
internal class TrackSourceCooldowns(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): Map<String, Long> = decodeSourceCooldowns(prefs.getStringSet(KEY_COOLDOWNS, null).orEmpty())

    @Synchronized
    fun put(source: String, until: Long, now: Long = System.currentTimeMillis()) {
        val cooldowns = read() + (source to until)
        prefs.edit { putStringSet(KEY_COOLDOWNS, encodeSourceCooldowns(cooldowns, now)) }
    }

    fun readChallenged(): Set<String> = prefs.getStringSet(KEY_CHALLENGED, null).orEmpty().toSet()

    /** After a run that heard a challenge from [challenged] and an answer from [answered]. */
    @Synchronized
    fun updateChallenged(challenged: Set<String>, answered: Set<String>) {
        val next = nextChallengedSources(readChallenged(), challenged, answered)
        prefs.edit { putStringSet(KEY_CHALLENGED, next) }
    }

    private companion object {
        const val PREFS_NAME = "tracker_source_cooldowns"
        const val KEY_COOLDOWNS = "cooldowns"
        const val KEY_CHALLENGED = "challenged"
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429
