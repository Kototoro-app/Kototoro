package org.skepsun.kototoro.mihon

import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val CAUSE_CHAIN_LIMIT = 8

/**
 * Returns the error a Mihon source call should surface to the app.
 *
 * Extension-lib wraps host errors before they reach the repository: `Call.await()` re-wraps every
 * OkHttp failure in a plain [IOException] and Rx/blocking bridges wrap in [RuntimeException].
 * Interactive errors buried in that chain are returned as-is so the UI can resolve them.
 */
internal fun unwrapMihonFailure(error: Throwable): Throwable {
    if (error is CancellationException) return error
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < CAUSE_CHAIN_LIMIT) {
        if (current is CloudFlareException || current is InteractiveActionRequiredException) {
            return current
        }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    val cause = error.cause
    return if (error !is IOException && error is RuntimeException && cause is IOException) cause else error
}
