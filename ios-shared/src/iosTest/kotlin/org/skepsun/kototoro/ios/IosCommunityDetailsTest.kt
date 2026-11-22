@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.community.domain.*
import org.skepsun.kototoro.community.nostr.*
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosCommunityDetailsTest {
    @Test
    fun ratingUsesTheIndexAndCommentsRespectModerationDeletesAndSpoilers() = withLibrary { library ->
        val work = SourceContent(101L, "Moon Story", emptySet(), "/work", "https://example.invalid/work",
            0f, null, null, emptySet(), null, emptySet(), SourceRef("TEST", "en", "MANGA"))
        val key = checkNotNull(CommunityWorkKeys.titleKey(work.title, CommunityMedium.COMIC))
        val keys = listOf(key)
        val author = checkNotNull(NostrKeyPair.fromSecretHex("1".padStart(64, '0')))
        fun comment(text: String, time: Long, parent: NostrEvent? = null, spoiler: Boolean = false): NostrEvent =
            NostrKeys.sign(CommunityEventSchema.comment(keys, text, work.title, time, parent, spoiler = spoiler), author)
        val root = comment("Root", 1)
        val reply = comment("Plot detail", 2, root, spoiler = true)
        val blocked = comment("Blocked content", 3)
        val removed = comment("Deleted content", 4)
        val hidden = comment("Index-hidden content", 5)
        val invalid = comment("Forged", 6).copy(sig = "0".repeat(128))
        val deletion = NostrKeys.sign(CommunityEventSchema.deletion(removed, 7), author)
        val profile = NostrKeys.sign(NostrUnsignedEvent(
            createdAt = 8, kind = 0, tags = emptyList(), content = """{"name":"Author","about":"Keep this"}""",
        ), author)
        val events = mutableListOf(root, reply, blocked, removed, hidden, invalid, deletion, profile)
        var connections = 0
        var accepting = false
        val submitted = mutableListOf<NostrEvent>()
        val relay = NostrRelayClient(NostrRelayConnector {
            connections++
            object : NostrRelayConnection {
                private val messages = ArrayDeque<String>()
                override suspend fun send(text: String) {
                    val frame = Json.parseToJsonElement(text).jsonArray
                    if (frame[0].jsonPrimitive.content == "EVENT") {
                        val event = checkNotNull(NostrEvent.fromJson(frame[1].jsonObject))
                        submitted += event
                        messages += """["OK","${event.id}",$accepting,"fixture"]"""
                        return
                    }
                    if (frame[0].jsonPrimitive.content != "REQ") return
                    val id = frame[1].jsonPrimitive.content
                    events.forEach { messages += """["EVENT","$id",${it.toJson()}]""" }
                    messages += """["EOSE","$id"]"""
                }
                override suspend fun receive(): String? = messages.removeFirstOrNull()
                override suspend fun close() = Unit
            }
        })
        val group = "moon"
        val rating = CommunityRatingSummary(8.3, 8.0, 6, 5.0, emptyList())
        val summary = CommunityWorkSummary(group, "comic", work.title, rating, comments = 5, hiddenComments = listOf(hidden.id))
        val files = mapOf(
            CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key)) to Json.encodeToString(mapOf(key to group)),
            CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(group)) to Json.encodeToString(mapOf(group to summary)),
            CommunitySnapshotShards.MODERATION_PATH to Json.encodeToString(CommunityModeration(blockedWords = listOf("blocked"))),
        )
        val manifest = CommunitySnapshotManifest(
            generatedAt = 1, relays = listOf("wss://fixture.invalid"), powRating = 0, powComment = 0, minChartWeight = 5.0,
            files = files.mapValues { "fixture" }, stats = CommunitySnapshotStats(0, 0, 0, 0, 1),
        )
        fun details(moderationAvailable: Boolean) = IosCommunityDetails(library, IosCommunityIndex { path ->
            when {
                path == CommunitySnapshotShards.MANIFEST_PATH -> Json.encodeToString(manifest)
                path == CommunitySnapshotShards.MODERATION_PATH && !moderationAvailable -> null
                else -> files[path]
            }
        }, { relay })
        val content = IosSourceContent(work)
        val community = details(true)
        val overview = checkNotNull(community.overview(content))
        assertEquals(8.0, overview.score, "the mean readers gave, not the Bayesian score the charts order by")
        assertEquals(6, overview.ratingCount)
        assertEquals(listOf(0, 0, 0, 0, 0), overview.starCounts)
        // No chart file exists, so the small index is listed whole.
        val highlights = community.highlights("week")
        assertTrue(highlights.isFallback)
        assertEquals(listOf(work.title), highlights.entries.map { it.title })
        assertEquals(0, connections)
        val comments = community.comments(content)
        assertEquals(listOf("Root", "Plot detail"), comments.map { it.content })
        assertTrue(comments.last().spoiler && comments.last().isReply)
        assertNull(community.overview(IosSourceContent(work.copy(contentRating = "ADULT"))))
        assertFailsWith<IllegalStateException> { details(false).comments(content) }
        assertEquals("Author", comments.first().author)
        // The shared service reads its built-in relays besides the manifest's; the fixture answers every address.
        assertTrue(connections >= 3, "Comment, deletion and nickname queries should reach the relays")

        val identity = IosCommunityIdentity.create()
        assertNull(IosCommunityIdentity.restore("invalid"))
        assertEquals(identity.publicKey, IosCommunityIdentity.restore(identity.recoveryKey())?.publicKey)
        assertEquals(false, community.rate(content, 6, identity).published)
        assertEquals(false, community.rate(content, 9, identity).published)
        assertEquals(9, community.myRating(content))
        assertEquals(1, community.pendingCount(), "Only the latest rating stays queued")
        val queued = community.postComment(content, "A public comment", spoiler = true, identity = identity)
        assertEquals(false, queued.published)
        assertEquals(2, queued.pendingCount)
        assertTrue(details(true).comments(content).single { it.content == "A public comment" }.isPending)
        val anotherWork = IosSourceContent(work.copy(id = 102L, title = "A different story"))
        assertTrue(community.comments(anotherWork).isEmpty())
        accepting = true
        assertEquals(0, community.retryPending(identity))
        assertFalse(community.comments(content).single { it.content == "A public comment" }.isPending)
        // Each event goes to every relay; the last two distinct ones are the retried rating and comment.
        val retried = submitted.distinctBy { it.id }.takeLast(2)
        assertTrue(retried.all(NostrKeys::verify))
        assertEquals(9, retried.firstNotNullOf { CommunityEventSchema.parseRating(it) }.stars)
        val posted = retried.single { it.kind == CommunityEventSchema.KIND_COMMENT }
        assertTrue(checkNotNull(CommunityEventSchema.parseComment(posted)).spoiler)
        val restoredAuthor = checkNotNull(IosCommunityIdentity.restore(NostrBech32.encodeSecretKey(author)))
        assertEquals("Author", community.nickname(restoredAuthor))
        assertTrue(community.setNickname(" New nickname ", restoredAuthor).published)
        assertEquals("Keep this", Json.parseToJsonElement(submitted.last().content).jsonObject["about"]?.jsonPrimitive?.content)
        assertEquals("New nickname", community.comments(content).first { it.content == "Root" }.author)
        accepting = false
        assertFalse(community.setNickname("My nickname", identity).published)
        assertEquals("My nickname", details(true).nickname(identity))
        assertEquals("My nickname", community.comments(content).single { it.id == posted.id }.author)
        events += posted
        assertFalse(community.comments(content).single { it.id == posted.id }.isPending)
    }

    private fun withLibrary(block: suspend (IosLibrary) -> Unit) = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val library = IosLibrary("$directory/library.db")
        try { block(library) } finally {
            library.close()
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
        Unit
    }
}
