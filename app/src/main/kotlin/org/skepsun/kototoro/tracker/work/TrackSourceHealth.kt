package org.skepsun.kototoro.tracker.work

import eu.kanade.tachiyomi.network.HttpException
import org.jsoup.HttpStatusException
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.tracker.domain.SourceHealth
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions as LegacyTooManyRequestExceptions

/**
 * The source's health after a check that failed with [error], or got an answer when it is null. Sources report
 * a rate limit in their own way: the app's interceptor and Kotatsu parsers raise TooManyRequestExceptions with the
 * Retry-After delay, Mihon extensions an HttpException, Jsoup an HttpStatusException; all may come wrapped.
 */
internal fun SourceHealth.afterCheck(error: Throwable?, now: Long): SourceHealth {
    val causes = generateSequence(error) { e -> e.cause?.takeIf { it !== e } }
    for (e in causes) {
        when (e) {
            is CloudFlareException, is InteractiveActionRequiredException -> return afterChallenge()
            is TooManyRequestExceptions -> return afterRateLimit(now, e.getRetryDelay())
            is LegacyTooManyRequestExceptions -> return afterRateLimit(now, e.getRetryDelay())
            is HttpException -> if (e.code == HTTP_TOO_MANY_REQUESTS) return afterRateLimit(now, null)
            is HttpStatusException -> if (e.statusCode == HTTP_TOO_MANY_REQUESTS) return afterRateLimit(now, null)
        }
    }
    return afterAnswer()
}

private const val HTTP_TOO_MANY_REQUESTS = 429
