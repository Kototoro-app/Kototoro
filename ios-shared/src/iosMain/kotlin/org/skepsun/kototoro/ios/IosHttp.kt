package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.skepsun.kototoro.tvbox.TvBoxHttp

/** Native HTTP for sources that run without the embedded JVM, on Apple's URL loading system. */
internal object IosHttp : TvBoxHttp {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    private val client by lazy {
        HttpClient(Darwin) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
            }
        }
    }

    internal val communitySnapshot by lazy {
        org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher(client)
    }

    internal val communityRelays by lazy {
        val sockets = client.config { install(WebSockets) }
        org.skepsun.kototoro.community.nostr.NostrRelayClient(
            org.skepsun.kototoro.community.nostr.KtorNostrRelayConnector(sockets),
            connectTimeoutMillis = 5_000L,
        )
    }

    override suspend fun get(url: String, headers: Map<String, String>): String {
        val response = client.get(url) {
            headers.forEach { (name, value) -> header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", DEFAULT_USER_AGENT)
        }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
        return response.bodyAsText()
    }
}
