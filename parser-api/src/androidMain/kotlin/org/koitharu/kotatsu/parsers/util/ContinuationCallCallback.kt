package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal class ContinuationCallCallback(
    private val call: Call,
    private val continuation: CancellableContinuation<Response>,
) : Callback, CompletionHandler {

    override fun onResponse(call: Call, response: Response) {
        continuation.resume(response) { _, value, _ ->
            value.closeQuietly()
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        continuation.resumeWithException(e)
    }

    override fun invoke(cause: Throwable?) {
        runCatching {
            call.cancel()
        }.onFailure { e ->
            cause?.addSuppressed(e)
        }
    }
}
