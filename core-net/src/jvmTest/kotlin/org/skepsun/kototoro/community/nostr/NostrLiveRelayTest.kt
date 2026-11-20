package org.skepsun.kototoro.community.nostr

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Live, read-only: `-PnostrLiveRelays=wss://relay.damus.io,wss://nos.lol`. Reads recent events other clients signed and
 * keeps only those [NostrKeys.verify] accepts, so a non-empty result shows the id serialization and signature check
 * agree with the rest of the network. Nothing is published.
 */
@EnabledIfSystemProperty(named = "nostrLiveRelays", matches = ".+")
class NostrLiveRelayTest {

    private val relays = System.getProperty("nostrLiveRelays").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun httpClient(): HttpClient {
        val proxy = (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY"))?.takeIf { it.startsWith("http") }
        return HttpClient(Java) {
            install(WebSockets)
            engine { if (proxy != null) this.proxy = ProxyBuilder.http(Url(proxy)) }
        }
    }

    /**
     * Writes to public relays, so it also needs `-PnostrLivePublish=true`. Publishes one comment from a throwaway key,
     * labelled as a Kototoro test and attached to `kototoro:test:p1` (no real work), reads it back by its `I` tag, then
     * asks the relays to delete it (NIP-09) and reports which did. Relays may keep it regardless.
     */
    @Test
    @EnabledIfSystemProperty(named = "nostrLivePublish", matches = "true")
    fun `a comment published to real relays reads back by its tag and its deletion is honoured or reported`() = runBlocking {
        val http = httpClient()
        try {
            val pool = NostrRelayPool(NostrRelayClient(KtorNostrRelayConnector(http)), relays, minAccepted = 2)
            val keys = NostrKeyPair.generate()
            val target = "kototoro:test:p1"
            val now = System.currentTimeMillis() / 1000
            val draft = NostrUnsignedEvent(
                createdAt = now,
                kind = 1111,
                tags = listOf(listOf("I", target), listOf("K", "kototoro"), listOf("i", target), listOf("k", "kototoro")),
                content = "Kototoro community protocol test (P1). Safe to ignore; a deletion request follows.",
            )
            val comment = NostrKeys.sign(NostrPow.mine(draft, keys.publicKeyHex, difficulty = 8), keys)
            println("throwaway author npub: ${NostrBech32.encodePublicKey(keys.publicKeyHex)}")
            println("comment id: ${comment.id}  pow bits: ${NostrPow.leadingZeroBits(comment.id)}")

            val published = pool.publish(comment)
            published.results.forEach { println("  publish ${it.relay}: accepted=${it.accepted} ${it.message}") }
            assertTrue(published.isPublished, "fewer than ${published.required} relays stored the comment")

            val byTag = pool.query(listOf(NostrFilter(kinds = listOf(1111), tags = mapOf("I" to listOf(target)), since = now - 60)))
            byTag.relays.forEach { result -> println("  #I ${result.relay}: ${result.events.count { it.id == comment.id }} match, complete=${result.complete}") }
            assertTrue(byTag.events.any { it.id == comment.id }, "the comment did not read back by its I tag")
            assertTrue(byTag.events.first { it.id == comment.id }.let(NostrKeys::verify))

            val deletion = NostrKeys.sign(
                NostrUnsignedEvent(now + 1, 5, listOf(listOf("e", comment.id), listOf("k", "1111")), "Kototoro protocol test cleanup"),
                keys,
            )
            pool.publish(deletion).results.forEach { println("  delete ${it.relay}: accepted=${it.accepted} ${it.message}") }
            val after = pool.query(listOf(NostrFilter(ids = listOf(comment.id))))
            after.relays.forEach { result -> println("  after delete ${result.relay}: ${if (result.events.isEmpty()) "gone" else "still served"}") }
        } finally {
            http.close()
        }
    }

    @Test
    fun `events signed by other clients verify, and relays answer tag queries`() = runBlocking {
        val http = httpClient()
        try {
            val client = NostrRelayClient(KtorNostrRelayConnector(http))
            for (relay in relays) {
                val notes = client.query(relay, listOf(NostrFilter(kinds = listOf(1), limit = 20)))
                val comments = client.query(relay, listOf(NostrFilter(kinds = listOf(1111), limit = 20)))
                val external = comments.events.flatMap { it.tagValues("I") }.firstOrNull()
                val byTag = external?.let { client.query(relay, listOf(NostrFilter(kinds = listOf(1111), tags = mapOf("I" to listOf(it)), limit = 5))) }
                println("$relay  notes ${notes.events.size} (complete ${notes.complete}${notes.error?.let { ", $it" } ?: ""})  " +
                    "comments ${comments.events.size}  #I \"$external\" -> ${byTag?.events?.size}")
                println("   sample comment tags: ${comments.events.firstOrNull()?.tags?.take(6)}")
            }
            val pool = NostrRelayPool(client, relays)
            val merged = pool.query(listOf(NostrFilter(kinds = listOf(1), limit = 20)))
            println("pool: ${merged.events.size} distinct verified notes from ${merged.relays.count { it.events.isNotEmpty() }} relays")
            assertTrue(merged.events.isNotEmpty(), "no relay returned an event that verifies")
        } finally {
            http.close()
        }
    }
}
