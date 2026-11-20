package org.skepsun.kototoro.community.nostr

import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** One open socket to a relay, text frames only. */
interface NostrRelayConnection {
    suspend fun send(text: String)

    /** The next text frame, or null once the relay has closed the socket. */
    suspend fun receive(): String?

    suspend fun close()
}

fun interface NostrRelayConnector {
    suspend fun connect(url: String): NostrRelayConnection
}

/** Connects over the platform's Ktor client, which must have the [WebSockets] plugin installed. */
class KtorNostrRelayConnector(private val httpClient: HttpClient) : NostrRelayConnector {

    init {
        requireNotNull(httpClient.pluginOrNull(WebSockets)) { "The Nostr client needs Ktor's WebSockets plugin" }
    }

    override suspend fun connect(url: String): NostrRelayConnection {
        val session = httpClient.webSocketSession(url)
        return object : NostrRelayConnection {
            override suspend fun send(text: String) = session.send(Frame.Text(text))

            override suspend fun receive(): String? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                    if (frame is Frame.Text) return frame.readText()
                }
            }

            override suspend fun close() = session.close()
        }
    }
}

data class NostrPublishResult(val relay: String, val accepted: Boolean, val message: String)

data class NostrQueryResult(
    val relay: String,
    val events: List<NostrEvent>,
    /** True when the relay said it had sent everything it stores (EOSE) before the timeout. */
    val complete: Boolean,
    val error: String? = null,
)

/**
 * NIP-01 over one relay at a time. Every event a relay hands back is checked: a bad id or signature, or an event that
 * does not match what was asked, is dropped, so nothing a relay makes up reaches the app.
 */
class NostrRelayClient(
    private val connector: NostrRelayConnector,
    /** A relay that neither answers nor refuses would otherwise hold a read or a write forever. */
    private val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    suspend fun publish(relay: String, event: NostrEvent, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): NostrPublishResult =
        withConnection(relay, onError = { NostrPublishResult(relay, accepted = false, message = it) }) { connection ->
            connection.send(NostrClientMessage.event(event))
            withTimeoutOrNull(timeoutMillis) { awaitOk(relay, connection, event.id) }
                ?: NostrPublishResult(relay, accepted = false, message = "timeout")
        }

    /** Stored events matching [filters], until the relay's EOSE or [timeoutMillis]. */
    suspend fun query(relay: String, filters: List<NostrFilter>, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): NostrQueryResult =
        withConnection(relay, onError = { NostrQueryResult(relay, emptyList(), complete = false, error = it) }) { connection ->
            val subscription = newSubscriptionId()
            val events = LinkedHashMap<String, NostrEvent>()
            connection.send(NostrClientMessage.request(subscription, filters))
            val complete = withTimeoutOrNull(timeoutMillis) { collectStored(connection, subscription, filters, events) } ?: false
            runCatching { connection.send(NostrClientMessage.close(subscription)) }
            NostrQueryResult(relay, events.values.toList(), complete)
        }

    /**
     * Stored and then live events matching [filters], for as long as it is collected. Ends when the relay closes the
     * socket or the subscription; reconnecting is the caller's choice.
     */
    fun subscribe(relay: String, filters: List<NostrFilter>): Flow<NostrEvent> = flow {
        val connection = withTimeoutOrNull(connectTimeoutMillis) { connector.connect(relay) }
            ?: throw IllegalStateException("$relay did not answer")
        val subscription = newSubscriptionId()
        try {
            connection.send(NostrClientMessage.request(subscription, filters))
            while (true) {
                val text = connection.receive() ?: break
                when (val message = NostrRelayMessage.parse(text)) {
                    is NostrRelayMessage.Event -> if (message.subscriptionId == subscription && accepts(message.event, filters)) {
                        emit(message.event)
                    }
                    is NostrRelayMessage.Closed -> if (message.subscriptionId == subscription) break
                    else -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
                    runCatching { connection.send(NostrClientMessage.close(subscription)) }
                    runCatching { connection.close() }
                }
            }
        }
    }

    private suspend fun awaitOk(relay: String, connection: NostrRelayConnection, eventId: String): NostrPublishResult {
        while (true) {
            val text = connection.receive() ?: return NostrPublishResult(relay, accepted = false, message = "closed")
            val message = NostrRelayMessage.parse(text)
            if (message is NostrRelayMessage.Ok && message.eventId == eventId) {
                return NostrPublishResult(relay, message.accepted, message.message)
            }
        }
    }

    /** Fills [into] until the relay's EOSE (true) or until it closes the socket or the subscription (false). */
    private suspend fun collectStored(
        connection: NostrRelayConnection,
        subscription: String,
        filters: List<NostrFilter>,
        into: MutableMap<String, NostrEvent>,
    ): Boolean {
        while (true) {
            val text = connection.receive() ?: return false
            when (val message = NostrRelayMessage.parse(text)) {
                is NostrRelayMessage.Event -> if (message.subscriptionId == subscription && accepts(message.event, filters)) {
                    into.getOrPut(message.event.id) { message.event }
                }
                is NostrRelayMessage.EndOfStoredEvents -> if (message.subscriptionId == subscription) return true
                is NostrRelayMessage.Closed -> if (message.subscriptionId == subscription) return false
                else -> Unit
            }
        }
    }

    private fun accepts(event: NostrEvent, filters: List<NostrFilter>): Boolean =
        filters.any { it.matches(event) } && NostrKeys.verify(event)

    private suspend fun <T> withConnection(relay: String, onError: (String) -> T, block: suspend (NostrRelayConnection) -> T): T {
        val connection = try {
            withTimeoutOrNull(connectTimeoutMillis) { connector.connect(relay) } ?: return onError("connect timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return onError(e.message ?: e::class.simpleName.orEmpty())
        }
        return try {
            block(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            // Bounded: this runs even when the caller has given up, and a relay that never answers must not keep it.
            withContext(NonCancellable) { withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { runCatching { connection.close() } } }
        }
    }

    private fun newSubscriptionId(): String = "kt" + Random.nextLong().toULong().toString(36)

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val CLOSE_TIMEOUT_MILLIS = 2_000L
    }
}
