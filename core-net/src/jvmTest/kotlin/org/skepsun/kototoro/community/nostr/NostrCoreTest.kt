package org.skepsun.kototoro.community.nostr

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NostrCoreTest {

    private val keys = NostrKeyPair.fromSecretHex("0000000000000000000000000000000000000000000000000000000000000003")!!

    private fun unsigned(content: String = "hello", tags: List<List<String>> = listOf(listOf("i", "https://anilist.co/manga/1"))) =
        NostrUnsignedEvent(createdAt = 1_700_000_000, kind = 1111, tags = tags, content = content)

    @Test
    fun `the public key is the BIP-340 x-only key of the secret`() {
        // BIP-340 test vector 0: secret key 3.
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", keys.publicKeyHex)
        assertNull(NostrKeyPair.fromSecretHex("00".repeat(32)), "zero is not a key")
        assertNull(NostrKeyPair.fromSecretHex("ff".repeat(32)), "above the curve order is not a key")
        assertNull(NostrKeyPair.fromSecretHex("abc"))
        val generated = NostrKeyPair.generate()
        assertEquals(64, generated.publicKeyHex.length)
        assertEquals(generated.publicKeyHex, NostrKeyPair.fromSecretHex(generated.secretKeyHex)!!.publicKeyHex)
    }

    @Test
    fun `ids hash exactly the NIP-01 serialization`() {
        assertEquals(
            """[0,"ab",1,7,[["e","x"],["t"]],"a\nb\"c\\d\re\tf\bg\fh${'\u0001'}i/j中😀"]""",
            NostrEvent.serializeForId("ab", 1, 7, listOf(listOf("e", "x"), listOf("t")), "a\nb\"c\\d\re\tf\bg\u000Ch\u0001i/j中😀"),
        )
        assertEquals("[0,\"ab\",1,7,[],\"\"]", NostrEvent.serializeForId("ab", 1, 7, emptyList(), ""))
    }

    @Test
    fun `a signed event verifies and any change to it does not`() {
        val event = NostrKeys.sign(unsigned(), keys)
        assertEquals(keys.publicKeyHex, event.pubkey)
        assertTrue(NostrKeys.verify(event))
        assertFalse(NostrKeys.verify(event.copy(content = "changed")))
        assertFalse(NostrKeys.verify(event.copy(tags = emptyList())))
        val other = NostrKeys.sign(unsigned(), NostrKeyPair.generate())
        assertFalse(NostrKeys.verify(event.copy(sig = other.sig)))
        assertFalse(NostrKeys.verify(event.copy(pubkey = other.pubkey)))
        assertFalse(NostrKeys.verify(event.copy(sig = "zz")))
        assertEquals(event, NostrEvent.fromJson(Json.parseToJsonElement(event.toJson().toString()).jsonObject))
    }

    @Test
    fun `NIP-19 keys round trip and a damaged one is refused`() {
        // The examples in NIP-19.
        val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        val pubHex = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
        assertEquals(pubHex, NostrBech32.decode(npub, NostrBech32.PUBLIC_PREFIX))
        assertEquals(npub, NostrBech32.encodePublicKey(pubHex))
        val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        assertEquals("67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa", NostrBech32.decode(nsec, NostrBech32.SECRET_PREFIX))

        val recovery = NostrBech32.encodeSecretKey(keys)
        assertEquals(keys.secretKeyHex, NostrBech32.decode(" ${recovery.chunked(10).joinToString(" ")}\n", NostrBech32.SECRET_PREFIX))
        assertNull(NostrBech32.decode(npub, NostrBech32.SECRET_PREFIX), "a public key is not a recovery key")
        assertNull(NostrBech32.decode(recovery.dropLast(1), NostrBech32.SECRET_PREFIX))
        val typo = recovery.substring(0, 20) + (if (recovery[20] == 'q') 'p' else 'q') + recovery.substring(21)
        assertNull(NostrBech32.decode(typo, NostrBech32.SECRET_PREFIX), "one wrong character fails the checksum")
        assertNull(NostrBech32.decode(recovery.uppercase().replaceFirst("N", "n"), NostrBech32.SECRET_PREFIX), "mixed case")
    }

    @Test
    fun `proof of work counts zero bits and only counts when committed`() = runTest {
        assertEquals(0, NostrPow.leadingZeroBits("8" + "0".repeat(63)))
        assertEquals(3, NostrPow.leadingZeroBits("1f"))
        assertEquals(1, NostrPow.leadingZeroBits("7f"))
        assertEquals(12, NostrPow.leadingZeroBits("000f"))
        assertEquals(256, NostrPow.leadingZeroBits("0".repeat(64)))

        val mined = NostrPow.mine(unsigned(), keys.publicKeyHex, difficulty = 12)
        val event = NostrKeys.sign(mined, keys)
        assertTrue(NostrPow.leadingZeroBits(event.id) >= 12)
        assertTrue(NostrPow.satisfies(event, 12))
        assertFalse(NostrPow.satisfies(event, 40), "committed to 12, not 40")
        assertEquals(1, event.tags.count { it.first() == "nonce" })
        assertTrue(NostrPow.satisfies(NostrKeys.sign(unsigned(), keys), 0))
        assertFalse(NostrPow.satisfies(NostrKeys.sign(unsigned(), keys), 1))
    }

    @Test
    fun `filters match the way relays do and serialize their tag letters`() {
        val event = NostrKeys.sign(unsigned(), keys)
        assertTrue(NostrFilter(kinds = listOf(1111), tags = mapOf("i" to listOf("https://anilist.co/manga/1", "x"))).matches(event))
        assertFalse(NostrFilter(kinds = listOf(1)).matches(event))
        assertFalse(NostrFilter(tags = mapOf("i" to listOf("x"))).matches(event))
        assertFalse(NostrFilter(since = event.createdAt + 1).matches(event))
        assertTrue(NostrFilter(authors = listOf(keys.publicKeyHex), until = event.createdAt).matches(event))
        assertEquals(
            """{"kinds":[1111],"#I":["a"],"#i":["b"],"since":5,"limit":10}""",
            NostrFilter(kinds = listOf(1111), tags = mapOf("i" to listOf("b"), "I" to listOf("a")), since = 5, limit = 10).toJson().toString(),
        )
    }

    @Test
    fun `relay messages parse and anything unknown is ignored`() {
        val event = NostrKeys.sign(unsigned(), keys)
        assertEquals(NostrRelayMessage.Event("s", event), NostrRelayMessage.parse("""["EVENT","s",${event.toJson()}]"""))
        assertEquals(NostrRelayMessage.EndOfStoredEvents("s"), NostrRelayMessage.parse("""["EOSE","s"]"""))
        assertEquals(NostrRelayMessage.Ok("id", false, "pow: difficulty 8 < 16"), NostrRelayMessage.parse("""["OK","id",false,"pow: difficulty 8 < 16"]"""))
        assertEquals(NostrRelayMessage.Closed("s", "rate-limited"), NostrRelayMessage.parse("""["CLOSED","s","rate-limited"]"""))
        assertEquals(NostrRelayMessage.Notice("hi"), NostrRelayMessage.parse("""["NOTICE","hi"]"""))
        assertNull(NostrRelayMessage.parse("""["AUTH","challenge"]"""))
        assertNull(NostrRelayMessage.parse("""["EVENT","s",{"id":1}]"""))
        assertNull(NostrRelayMessage.parse("not json"))
        assertEquals("""["CLOSE","s"]""", NostrClientMessage.close("s"))
    }

    /** A relay in memory: answers REQ from [stored], OK to EVENT with [accept], and records what the client sent. */
    private class FakeRelay(
        val stored: List<NostrEvent> = emptyList(),
        val accept: Boolean = true,
        val sendEose: Boolean = true,
        val extra: List<String> = emptyList(),
    ) : NostrRelayConnector {
        val sent = ArrayList<String>()
        var closed = 0

        override suspend fun connect(url: String): NostrRelayConnection = object : NostrRelayConnection {
            private val inbox = ArrayDeque<String>()

            override suspend fun send(text: String) {
                sent += text
                val array = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
                val type = array[0].toString().trim('"')
                when (type) {
                    "EVENT" -> {
                        val id = array[1].jsonObject["id"].toString().trim('"')
                        inbox += """["OK","$id",$accept,"${if (accept) "" else "blocked: no"}"]"""
                    }
                    "REQ" -> {
                        val sub = array[1].toString().trim('"')
                        inbox += extra.map { it.replace("SUB", sub) }
                        stored.forEach { inbox += """["EVENT","$sub",${it.toJson()}]""" }
                        if (sendEose) inbox += """["EOSE","$sub"]"""
                    }
                }
            }

            override suspend fun receive(): String? {
                while (inbox.isEmpty()) {
                    if (!sendEose) kotlinx.coroutines.delay(1_000) else return null
                }
                return inbox.removeFirst()
            }

            override suspend fun close() {
                closed++
            }
        }
    }

    @Test
    fun `publishing waits for the relay's OK for that event`() = runTest {
        val event = NostrKeys.sign(unsigned(), keys)
        val relay = FakeRelay()
        assertEquals(NostrPublishResult("wss://a", true, ""), NostrRelayClient(relay).publish("wss://a", event))
        assertEquals(1, relay.closed)
        val refused = NostrRelayClient(FakeRelay(accept = false)).publish("wss://a", event)
        assertFalse(refused.accepted)
        assertEquals("blocked: no", refused.message)
        val broken = NostrRelayClient(NostrRelayConnector { throw IllegalStateException("refused") }).publish("wss://a", event)
        assertEquals(NostrPublishResult("wss://a", false, "refused"), broken)
    }

    @Test
    fun `a query keeps only signed events that match and stops at EOSE`() = runTest {
        val good = NostrKeys.sign(unsigned(), keys)
        val forged = good.copy(content = "forged")
        val unrelated = NostrKeys.sign(unsigned(tags = listOf(listOf("i", "other"))), keys)
        val filter = NostrFilter(kinds = listOf(1111), tags = mapOf("i" to listOf("https://anilist.co/manga/1")))
        val relay = FakeRelay(stored = listOf(good, forged, unrelated, good))
        val result = NostrRelayClient(relay).query("wss://a", listOf(filter))
        assertEquals(listOf(good), result.events)
        assertTrue(result.complete)
        assertTrue(relay.sent.last().startsWith("[\"CLOSE\""), "the subscription is closed when done")

        val slow = NostrRelayClient(FakeRelay(stored = listOf(good), sendEose = false)).query("wss://a", listOf(filter), timeoutMillis = 5_000)
        assertEquals(listOf(good), slow.events, "what came before the timeout is kept")
        assertFalse(slow.complete)
    }

    @Test
    fun `a relay that never answers or never lets go costs a timeout, not the operation`() = runTest {
        val event = NostrKeys.sign(unsigned(), keys)
        val silent = NostrRelayClient(NostrRelayConnector { kotlinx.coroutines.awaitCancellation() }, connectTimeoutMillis = 3_000)
        assertEquals(NostrPublishResult("wss://a", false, "connect timeout"), silent.publish("wss://a", event))
        assertEquals("connect timeout", silent.query("wss://a", listOf(NostrFilter())).error)

        val sticky = object : NostrRelayConnector {
            override suspend fun connect(url: String) = object : NostrRelayConnection {
                override suspend fun send(text: String) = Unit
                override suspend fun receive(): String? = kotlinx.coroutines.awaitCancellation()
                override suspend fun close() = kotlinx.coroutines.awaitCancellation()
            }
        }
        val result = NostrRelayClient(sticky).query("wss://a", listOf(NostrFilter()), timeoutMillis = 1_000)
        assertFalse(result.complete, "returns after the read timeout and a bounded close")
    }

    @Test
    fun `the pool needs enough relays to accept and merges what they return`() = runTest {
        val first = NostrKeys.sign(unsigned("a"), keys)
        val second = NostrKeys.sign(unsigned("b").copy(createdAt = 1_800_000_000), keys)
        val relays = mapOf("wss://a" to FakeRelay(stored = listOf(first)), "wss://b" to FakeRelay(stored = listOf(first, second), accept = false))
        val client = NostrRelayClient(NostrRelayConnector { url -> relays.getValue(url).connect(url) })
        val pool = NostrRelayPool(client, listOf("wss://a", "wss://b"), minAccepted = 2)
        val published = pool.publish(first)
        assertEquals(1, published.accepted)
        assertFalse(published.isPublished)
        assertTrue(NostrRelayPool(client, listOf("wss://a"), minAccepted = 2).publish(first).isPublished, "capped at the relay count")
        assertEquals(listOf(second, first), pool.query(listOf(NostrFilter(kinds = listOf(1111)))).events)
    }

    @Test
    fun `a subscription merges relays, drops repeats and closes when cancelled`() = runTest {
        val first = NostrKeys.sign(unsigned("a"), keys)
        val second = NostrKeys.sign(unsigned("b"), keys)
        val a = FakeRelay(stored = listOf(first), sendEose = false)
        val b = FakeRelay(stored = listOf(first, second), sendEose = false)
        val client = NostrRelayClient(NostrRelayConnector { url -> (if (url == "wss://a") a else b).connect(url) })
        val pool = NostrRelayPool(client, listOf("wss://a", "wss://b"))
        val received = ArrayList<NostrEvent>()
        val job = async { pool.subscribe(listOf(NostrFilter(kinds = listOf(1111)))).collect { received += it } }
        while (received.size < 2) yield()
        job.cancel()
        job.join()
        assertEquals(setOf(first, second), received.toSet())
        assertEquals(2, received.size)
        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
    }
}
