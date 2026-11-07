package org.skepsun.kototoro.tracker.work

import eu.kanade.tachiyomi.network.HttpException
import org.jsoup.HttpStatusException
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.tracker.domain.SourceAnswer
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

private const val HTTP_TOO_MANY_REQUESTS = 429
