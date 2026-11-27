package org.skepsun.kototoro.tracker.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.skepsun.kototoro.core.network.TrackerWebhookClient
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one webhook request; the notifier never throws, every failure is reported here. */
sealed interface TrackerWebhookResult {

    /** The endpoint answered with a 2xx response. */
    data class Sent(val code: Int) : TrackerWebhookResult

    /** The endpoint answered with a non-2xx response, or could not be reached at all. */
    data class Failed(val reason: String, val code: Int? = null) : TrackerWebhookResult

    /** The configured URL is empty, malformed or not `https://`. */
    data object InvalidUrl : TrackerWebhookResult
}

/**
 * Pushes "which tracked works got new chapters" to a user-configured webhook (Discord or any
 * JSON endpoint). This is unrelated to Discord Rich Presence: nothing here touches the scrobbling
 * side, no OAuth token, no presence state.
 *
 * Both [sendBatch] and [sendTest] swallow every failure (only cancellation propagates) so a broken
 * webhook can never fail [TrackWorker] or block the notification path.
 */
@Singleton
class TrackerWebhookNotifier @Inject constructor(
    @TrackerWebhookClient private val client: OkHttpClient,
) {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Sends one merged request for [items]; see [TrackerWebhookPayloads.buildBatch]. */
    suspend fun sendBatch(
        url: String,
        items: List<TrackerWebhookItem>,
        timestamp: Long = System.currentTimeMillis(),
    ): TrackerWebhookResult {
        if (items.isEmpty()) {
            return TrackerWebhookResult.Failed("nothing to send")
        }
        return send(url, TrackerWebhookPayloads.buildBatch(items, timestamp))
    }

    /** Sends a single test request so the user can check the URL without waiting for an update. */
    suspend fun sendTest(
        url: String,
        timestamp: Long = System.currentTimeMillis(),
    ): TrackerWebhookResult = send(url, TrackerWebhookPayloads.buildTest(timestamp))

    private suspend fun send(url: String, payload: TrackerWebhookPayload): TrackerWebhookResult {
        val normalizedUrl = TrackerWebhookUrl.normalize(url) ?: return TrackerWebhookResult.InvalidUrl
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", USER_AGENT)
                    .post(json.encodeToString(TrackerWebhookPayload.serializer(), payload).toRequestBody(mediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        TrackerWebhookResult.Sent(response.code)
                    } else {
                        TrackerWebhookResult.Failed("HTTP ${response.code}", response.code)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                e.printStackTraceDebug()
                TrackerWebhookResult.Failed(e.javaClass.simpleName)
            } catch (e: Throwable) {
                e.printStackTraceDebug()
                TrackerWebhookResult.Failed(e.javaClass.simpleName)
            }
        }
    }

    private companion object {

        const val USER_AGENT = "Kototoro"
    }
}
