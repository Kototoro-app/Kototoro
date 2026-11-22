package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunitySnapshotStats
import org.skepsun.kototoro.community.domain.CommunityWorkKeys
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.domain.CommunityWorkSource
import org.skepsun.kototoro.community.nostr.CommunitySourceHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrRelayConnection
import org.skepsun.kototoro.community.nostr.NostrRelayConnector
import org.skepsun.kototoro.core.library.community.CommunityFreshRatings
import org.skepsun.kototoro.core.library.community.CommunityIdentityStore
import org.skepsun.kototoro.core.library.community.CommunityService
import org.skepsun.kototoro.core.library.community.CommunitySnapshots
import org.skepsun.kototoro.core.library.community.CommunitySwitches
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopCommunityTest {
    @TempDir lateinit var directory: Path

    private val source = SourceRef("MANGA_SITE", "zh", "MANGA")
    private val work = SourceContent(
        7, "鬼滅之刃", emptySet(), "/w7", "https://fixture.invalid/w7", 0f, null, null, emptySet(), null, emptySet(), source,
    )

    @Test
    fun `the title fold matches Android's traditional to simplified conversion`() {
        assertEquals("鬼灭之刃", desktopCommunityFold("鬼滅之刃"))
        assertEquals("abc国语", desktopCommunityFold("ABC國語"))
        assertEquals("full", desktopCommunityFold("ＦＵＬＬ"))
    }

    @Test
    fun `a rating made offline waits in the outbox and is published once a relay answers`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val relay = FakeRelay(online = false)
            val service = service(runtime, relay)
            val identity = service.identity(work, resolve = false)
            assertTrue(identity.keys.isNotEmpty())
            assertEquals(CommunityRatingRefusal.NO_IDENTITY, service.refusal(identity))
            service.createIdentity()
            assertNull(service.rate(work.id, identity, 8))
            assertEquals(1, service.pendingCount(), "an unreachable relay keeps the rating queued")
            assertEquals(8, runtime.database.getCommunityDao().findRating(work.id)?.stars)

            relay.online = true
            assertEquals(0, service.flushOutbox(force = true))
            val published = relay.stored.single { it.kind == CommunityEventSchema.KIND_RATING }
            assertEquals(service.keys()?.publicKeyHex, published.pubkey)

            // Withdrawing replaces the rating with a withdrawal and forgets the stored stars.
            service.withdraw(work.id)
            assertNull(runtime.database.getCommunityDao().findRating(work.id))
            assertEquals(0, service.pendingCount())
        }
    }

    @Test
    fun `comments are posted, listed with their author, and hidden once reported or blocked`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val relay = FakeRelay(online = true)
            val mine = service(runtime, relay)
            mine.createIdentity()
            val identity = mine.identity(work, resolve = false)
            assertNull(mine.postComment(identity, "第一条", parent = null, spoiler = false, language = "zh"))

            // Another reader replies on the same relay.
            val other = service(runtime, relay, preferences = "desktop_community_other")
            other.createIdentity()
            val root = mine.comments(identity, null).threads.single().root
            assertTrue(root.isMine)
            assertNull(other.postComment(identity, "回复", parent = root.event, spoiler = true, language = "zh"))

            val page = mine.comments(identity, null)
            val thread = page.threads.single()
            assertEquals("第一条", thread.root.comment.content)
            val reply = thread.replies.single()
            assertFalse(reply.isMine)
            assertTrue(reply.comment.spoiler)
            assertTrue(page.relaysAnswered > 0)

            mine.report(reply.event, CommunityReportType.SPAM)
            assertTrue(mine.comments(identity, null).threads.single().replies.isEmpty(), "a reported comment is hidden here")
            mine.block(reply.comment.pubkey)
            assertTrue(relay.stored.any { it.kind == CommunityEventSchema.KIND_REPORT })
        }
    }

    @Test
    fun `refusals follow the switches and adult works need their own switch`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            var switches = CommunitySwitches(enabled = false)
            val service = service(runtime, FakeRelay(online = true)) { switches }
            service.createIdentity()
            val adult = work.copy(id = 8, source = SourceRef("ADULT", "zh", "HENTAI_MANGA"))
            assertEquals(CommunityRatingRefusal.DISABLED, service.refusal(service.identity(work, resolve = false)))
            switches = CommunitySwitches(enabled = true)
            assertNull(service.refusal(service.identity(work, resolve = false)))
            assertEquals(CommunityRatingRefusal.NSFW, service.refusal(service.identity(adult, resolve = false)))
            switches = CommunitySwitches(enabled = true, nsfwRatings = true)
            assertNull(service.refusal(service.identity(adult, resolve = false)))
        }
    }

    @Test
    fun `snapshot files are cached by hash and never written outside the cache`() {
        val root = directory.resolve("snapshot")
        val cache = DesktopCommunitySnapshotCache(root)
        cache.write("v1/manifest.json", "{}", "abc")
        assertEquals("{}", cache.read("v1/manifest.json"))
        assertEquals("abc", cache.readHash("v1/manifest.json"))
        cache.write("../escape.json", "x", "y")
        assertFalse(Files.exists(directory.resolve("escape.json")))
        cache.clear()
        assertNull(cache.read("v1/manifest.json"))
    }

    @Test
    fun `an event only one relay took stays until a second relay or an index run holds it`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val relay = FakeRelay(online = true).apply { reachable = { it == "wss://relay.nostr.wirednet.jp" } }
            var now = 1_800_000_000_000L
            var indexedAt = 1L
            val service = service(runtime, relay, now = { now }, manifest = { manifest(indexedAt) })
            service.createIdentity()
            val identity = service.identity(work, resolve = false)
            service.rate(work.id, identity, 9)
            val dao = runtime.database.getCommunityDao()
            assertEquals(0, service.pendingCount(), "one relay took it, so it is published")
            assertEquals(1, dao.countUnconfirmed(), "but the outbox keeps a copy until it is safe")

            // An hour on, a second relay answers and takes the copy: safe without the index.
            now += 61 * 60_000L
            relay.reachable = { it in setOf("wss://relay.nostr.wirednet.jp", "wss://nos.lol") }
            service.flushOutbox()
            assertEquals(0, dao.countUnconfirmed())

            // A comment only one relay takes is let go once the index has run after it was published.
            relay.reachable = { it == "wss://relay.nostr.wirednet.jp" }
            service.postComment(identity, "只有一个中继", parent = null, spoiler = false, language = "zh")
            assertEquals(1, dao.countUnconfirmed())
            now += 61 * 60_000L
            indexedAt = now / 1000
            service.flushOutbox()
            assertEquals(0, dao.countUnconfirmed())
        }
    }

    @Test
    fun `ratings newer than the snapshot are counted, the reader's own among them`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val relay = FakeRelay(online = true)
            val mine = service(runtime, relay)
            mine.createIdentity()
            val identity = mine.identity(work, resolve = false)
            assertEquals(CommunityFreshRatings(), mine.freshRatings(work.id, identity))
            mine.rate(work.id, identity, 7)
            val other = service(runtime, relay, preferences = "desktop_community_other")
            other.createIdentity()
            other.rate(99, identity, 4)
            assertEquals(CommunityFreshRatings(2, includesMine = true), mine.freshRatings(work.id, identity))
        }
    }

    @Test
    fun `a chart work opens the reader's own copy, else a source's, never an adult source's`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val key = checkNotNull(CommunityWorkKeys.titleKey("Moon Story", CommunityMedium.COMIC, ::desktopCommunityFold))
            val keysPath = CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key))
            val worksPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of("g1"))
            val files = mapOf(
                keysPath to Json.encodeToString(mapOf(key to "g1")),
                worksPath to Json.encodeToString(mapOf("g1" to CommunityWorkSummary("g1", "comic", "Moon Story"))),
            )
            val manifest = Json.encodeToString(CommunitySnapshotManifest(
                generatedAt = 1, relays = emptyList(), powRating = 0, powComment = 0, minChartWeight = 5.0,
                files = files.mapValues { "x" }, stats = CommunitySnapshotStats(0, 0, 0, 0, 1),
            ))
            val service = service(runtime, FakeRelay(online = true), manifest = { manifest }, files = files)
            fun item(id: Long, title: String, source: SourceRef = this@DesktopCommunityTest.source) = work.copy(id = id, title = title, source = source)
            val adult = SourceRef("ADULT", "zh", "HENTAI_MANGA")
            val asked = mutableListOf<String>()
            val search: suspend (SourceRef, String) -> List<SourceContent> = { source, _ ->
                asked += source.name
                listOf(item(8, "Another Story", source), item(9, "Moon Story", source))
            }
            val owned = service.findWork("g1", "Moon Story", listOf(item(5, "Moon Story")), listOf(adult, source), search = search)
            // The library's key shard is not cached yet, so the copy is found by searching; once cached, the library wins.
            assertEquals(9L, owned?.id)
            assertEquals(listOf(source.name), asked, "an adult source is never asked")
            assertEquals(5L, service.findWork("g1", "Moon Story", listOf(item(5, "Moon Story")), listOf(source), search = search)?.id)
            assertNull(service.findWork("g1", "Moon Story", emptyList(), listOf(source)) { _, _ -> listOf(item(8, "Another Story")) })
        }
    }

    @Test
    fun `posts name a shareable source unless the reader turned it off`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val relay = FakeRelay(online = true)
            var share = true
            val service = service(runtime, relay) { CommunitySwitches(enabled = true, shareSources = share) }
            service.createIdentity()
            val shared = work.copy(publicUrl = "https://www.Example.org/w7")
            service.rate(shared.id, service.identity(shared, resolve = false), 8)
            val hint = CommunityEventSchema.parseRating(relay.stored.last())?.source
            assertEquals(CommunitySourceHint("MANGA_SITE", "/w7", "example.org"), hint)
            val custom = shared.copy(id = 70, source = SourceRef("JSON_TVBOX_1", "zh", "VIDEO"))
            service.postComment(service.identity(custom, resolve = false), "自定义来源", null, false, null)
            assertNull(CommunityEventSchema.parseComment(relay.stored.last())?.source, "a user-configured source is never named")
            share = false
            service.rate(shared.id, service.identity(shared, resolve = false), 9)
            assertNull(CommunityEventSchema.parseRating(relay.stored.last())?.source)
        }
    }

    @Test
    fun `adult sources are named and searched only once adult ratings are on`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            var adultRatings = false
            val service = service(runtime, FakeRelay(online = true)) {
                CommunitySwitches(enabled = true, nsfwRatings = adultRatings)
            }
            val adultSource = SourceRef("ADULT", "zh", "HENTAI_MANGA")
            val adult = work.copy(id = 8, source = adultSource, publicUrl = "https://adult.example/w8")
            assertNull(service.identity(adult, resolve = false).source)
            val asked = mutableListOf<String>()
            val search: suspend (SourceRef, String) -> List<SourceContent> = { source, _ -> asked += source.name; emptyList() }
            service.findWork("g1", "Moon Story", emptyList(), listOf(adultSource, source), search = search)
            assertEquals(listOf(source.name), asked)

            adultRatings = true
            assertEquals("ADULT", service.identity(adult, resolve = false).source?.source)
            asked.clear()
            service.findWork("g1", "Moon Story", emptyList(), listOf(adultSource, source), search = search)
            assertEquals(listOf(adultSource.name, source.name), asked)
        }
    }

    @Test
    fun `the source its readers named is searched first and its named entry wins`() = runBlocking {
        DesktopRuntime.open(directory.resolve("data")).use { runtime ->
            val key = checkNotNull(CommunityWorkKeys.titleKey("Moon Story", CommunityMedium.COMIC, ::desktopCommunityFold))
            val keysPath = CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key))
            val worksPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of("g1"))
            val summary = CommunityWorkSummary("g1", "comic", "Moon Story", sources = listOf(
                CommunityWorkSource("NAMED", "/moon-2", "named.example", readers = 3),
                CommunityWorkSource("OTHER_ECOSYSTEM", "/m/1", "site.example", readers = 1),
            ))
            val files = mapOf(
                keysPath to Json.encodeToString(mapOf(key to "g1")),
                worksPath to Json.encodeToString(mapOf("g1" to summary)),
            )
            val manifest = Json.encodeToString(CommunitySnapshotManifest(
                generatedAt = 1, relays = emptyList(), powRating = 0, powComment = 0, minChartWeight = 5.0,
                files = files.mapValues { "x" }, stats = CommunitySnapshotStats(0, 0, 0, 0, 1),
            ))
            val service = service(runtime, FakeRelay(online = true), manifest = { manifest }, files = files)
            val mine = SourceRef("MINE", "zh", "MANGA")
            val named = SourceRef("NAMED", "zh", "MANGA")
            val sameSite = SourceRef("SAME_SITE", "zh", "MANGA")
            val hostOf: (SourceRef) -> String? = { if (it == sameSite) "site.example" else null }
            val options = service.sourceOptions("g1", listOf(mine, sameSite, named), hostOf, includeOthers = true)
            assertEquals(listOf("NAMED", "SAME_SITE", "MINE"), options.map { it.source.name },
                "named sources first, then the same site under another id, then the rest")
            val asked = mutableListOf<String>()
            val found = service.findWork("g1", "Moon Story", emptyList(), listOf(mine, sameSite, named), hostOf) { source, _ ->
                asked += source.name
                listOf(work.copy(id = 1, title = "Moon Story", url = "/moon-1", source = source),
                    work.copy(id = 2, title = "Moon Story", url = "/moon-2", source = source))
            }
            assertEquals(2L, found?.id, "the entry at the address its readers named")
            assertEquals(listOf("NAMED"), asked)
        }
    }

    private fun service(
        runtime: DesktopRuntime,
        relay: FakeRelay,
        preferences: String = "desktop_community",
        now: () -> Long = System::currentTimeMillis,
        manifest: () -> String = { MANIFEST },
        files: Map<String, String> = emptyMap(),
        switches: () -> CommunitySwitches = { CommunitySwitches(enabled = true) },
    ) = CommunityService(
        database = runtime.database,
        identities = MemoryIdentities(),
        blocklist = DesktopCommunityBlocklist(runtime.preferences.open(preferences)),
        snapshots = CommunitySnapshots(
            fetch = { path -> if (path == "v1/manifest.json") manifest() else files[path] },
            now = now,
            cache = DesktopCommunitySnapshotCache(directory.resolve("snapshot-$preferences")),
        ),
        relayClient = NostrRelayClient(relay, connectTimeoutMillis = 2_000),
        searchApi = null,
        switches = switches,
        fold = ::desktopCommunityFold,
        nowMillis = now,
    )

    private class MemoryIdentities : CommunityIdentityStore {
        private var keys: NostrKeyPair? = null
        override val hasIdentity: Boolean get() = keys != null
        override fun keys(): NostrKeyPair? = keys
        override fun store(keys: NostrKeyPair) {
            this.keys = keys
        }
        override fun clear() {
            keys = null
        }
    }

    /** One relay for every URL: stores what it is sent and answers queries from what it stored. */
    private class FakeRelay(@Volatile var online: Boolean) : NostrRelayConnector {
        val stored = java.util.concurrent.CopyOnWriteArrayList<NostrEvent>()
        /** Which relays answer at all while [online]; every one by default. */
        @Volatile var reachable: (String) -> Boolean = { true }

        override suspend fun connect(url: String): NostrRelayConnection {
            if (!online || !reachable(url)) throw IOException("offline")
            val replies = Channel<String>(Channel.UNLIMITED)
            return object : NostrRelayConnection {
                override suspend fun send(text: String) {
                    val message = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
                    when (message[0].jsonPrimitive.content) {
                        "EVENT" -> {
                            val event = requireNotNull(NostrEvent.fromJson(message[1].jsonObject))
                            if (stored.none { it.id == event.id }) stored += event
                            replies.send(buildJsonArray {
                                add(JsonPrimitive("OK")); add(JsonPrimitive(event.id)); add(JsonPrimitive(true)); add(JsonPrimitive(""))
                            }.toString())
                        }
                        "REQ" -> {
                            val subscription = message[1].jsonPrimitive.content
                            val filters = message.drop(2).map { filter(it.jsonObject) }
                            stored.filter { event -> filters.any { it.matches(event) } }.forEach { event ->
                                replies.send(buildJsonArray {
                                    add(JsonPrimitive("EVENT")); add(JsonPrimitive(subscription)); add(event.toJson())
                                }.toString())
                            }
                            replies.send(buildJsonArray { add(JsonPrimitive("EOSE")); add(JsonPrimitive(subscription)) }.toString())
                        }
                    }
                }

                override suspend fun receive(): String? = replies.receiveCatching().getOrNull()

                override suspend fun close() {
                    replies.close()
                }
            }
        }

        private fun filter(json: JsonObject) = NostrFilter(
            ids = (json["ids"] as? JsonArray)?.map { it.jsonPrimitive.content },
            authors = (json["authors"] as? JsonArray)?.map { it.jsonPrimitive.content },
            kinds = (json["kinds"] as? JsonArray)?.map { it.jsonPrimitive.int },
            tags = json.filterKeys { it.startsWith("#") }
                .mapKeys { it.key.drop(1) }.mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.content } },
        )
    }

    private fun manifest(generatedAt: Long) = MANIFEST.replace("\"generated_at\":1,", "\"generated_at\":$generatedAt,")

    private companion object {
        /** No relays of its own and no proof of work, so the test signs instantly. */
        const val MANIFEST = """{"version":1,"generated_at":1,"relays":[],"pow_rating":0,"pow_comment":0,
            "min_chart_weight":1.0,"files":{},"stats":{"events":0,"ratings":0,"comments":0,"raters":0,"works":0}}"""
    }
}
