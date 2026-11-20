package org.skepsun.kototoro.community.nostr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityEventSchemaTest {

    private val keys = NostrKeyPair.generate()
    private val workKeys = listOf("https://anilist.co/manga/30013", "kototoro:t:comic:onepiece")

    @Test
    fun `stars map to the normalized rating and back, and any client's value in range reads`() {
        assertEquals("0.1", CommunityEventSchema.ratingValue(1))
        assertEquals("0.8", CommunityEventSchema.ratingValue(8))
        assertEquals("1.0", CommunityEventSchema.ratingValue(10))
        (1..10).forEach { assertEquals(it, CommunityEventSchema.starsOf(CommunityEventSchema.ratingValue(it))) }
        assertEquals(6, CommunityEventSchema.starsOf("0.600"))
        assertEquals(1, CommunityEventSchema.starsOf("0"), "the lowest value is the lowest rating")
        assertNull(CommunityEventSchema.starsOf("1.5"))
        assertNull(CommunityEventSchema.starsOf("-0.1"))
        assertNull(CommunityEventSchema.starsOf("NaN"))
        assertNull(CommunityEventSchema.starsOf("great"))
    }

    @Test
    fun `a rating reads back as written and is addressed by medium and first key`() {
        val event = NostrKeys.sign(
            CommunityEventSchema.rating(workKeys, "comic", "MANGA", 8, " One Piece ", nsfw = false, createdAt = 100),
            keys,
        )
        assertEquals(listOf("comic:https://anilist.co/manga/30013"), event.tagValues("d"))
        assertTrue(CommunityEventSchema.isKototoro(event))
        assertTrue(CommunityEventSchema.indexFilter(since = 50).matches(event))
        val rating = CommunityEventSchema.parseRating(event)!!
        assertEquals(CommunityRating(event.id, keys.publicKeyHex, 100, "comic:https://anilist.co/manga/30013", workKeys, "comic", 8, "One Piece", false), rating)
    }

    @Test
    fun `a comment and its reply share the work's root keys`() {
        val comment = NostrKeys.sign(
            CommunityEventSchema.comment(workKeys, " Great arc ", "One Piece", 100, chapter = "1100", spoiler = true, language = "en"),
            keys,
        )
        assertEquals(workKeys, comment.tagValues("I"))
        assertEquals(listOf("web"), comment.tagValues("K"))
        assertEquals(workKeys, comment.tagValues("i"))
        val parsed = CommunityEventSchema.parseComment(comment)!!
        assertEquals("Great arc", parsed.content)
        assertEquals("1100", parsed.chapter)
        assertTrue(parsed.spoiler)
        assertEquals("en", parsed.language)
        assertNull(parsed.parentId)

        val replier = NostrKeyPair.generate()
        val reply = NostrKeys.sign(CommunityEventSchema.comment(workKeys, "Agreed", null, 101, parent = comment), replier)
        assertEquals(listOf("1111"), reply.tagValues("k"))
        assertEquals(listOf(keys.publicKeyHex), reply.tagValues("p"))
        assertTrue(reply.tagValues("i").isEmpty(), "only a top-level comment names the work as its parent")
        val parsedReply = CommunityEventSchema.parseComment(reply)!!
        assertEquals(comment.id, parsedReply.parentId)
        assertEquals(workKeys, parsedReply.keys)
        assertTrue(NostrFilter(kinds = listOf(1111), tags = mapOf("I" to listOf(workKeys[1]))).matches(reply))
    }

    @Test
    fun `deletions, withdrawals and reports parse`() {
        val rating = NostrKeys.sign(CommunityEventSchema.rating(workKeys, "comic", null, 5, null, false, 100), keys)
        val withdrawal = NostrKeys.sign(CommunityEventSchema.ratingWithdrawal(keys.publicKeyHex, "comic:${workKeys[0]}", 200), keys)
        assertEquals(setOf("34259:${keys.publicKeyHex}:comic:${workKeys[0]}"), CommunityEventSchema.parseDeletion(withdrawal)!!.addresses)
        val deletion = NostrKeys.sign(CommunityEventSchema.deletion(rating, 200), keys)
        assertEquals(setOf(rating.id), CommunityEventSchema.parseDeletion(deletion)!!.eventIds)

        val report = NostrKeys.sign(CommunityEventSchema.report(rating, CommunityReportType.SPAM, 300), NostrKeyPair.generate())
        val parsed = CommunityEventSchema.parseReport(report)!!
        assertEquals(rating.id, parsed.eventId)
        assertEquals(keys.publicKeyHex, parsed.reportedPubkey)
        assertEquals(CommunityReportType.SPAM, parsed.type)
    }

    @Test
    fun `events without the Kototoro label or with bad values are not read`() {
        val foreign = NostrKeys.sign(
            NostrUnsignedEvent(100, 34259, listOf(listOf("d", "movie:tt1"), listOf("rating", "0.9"), listOf("i", "x")), ""),
            keys,
        )
        assertNull(CommunityEventSchema.parseRating(foreign))
        assertFalse(CommunityEventSchema.indexFilter(since = null).matches(foreign))

        val rating = CommunityEventSchema.rating(workKeys, "comic", null, 5, null, false, 100)
        val badValue = rating.copy(tags = rating.tags.map { if (it[0] == "rating") listOf("rating", "7") else it })
        assertNull(CommunityEventSchema.parseRating(NostrKeys.sign(badValue, keys)))
        val noKeys = rating.copy(tags = rating.tags.filterNot { it[0] == "i" })
        assertNull(CommunityEventSchema.parseRating(NostrKeys.sign(noKeys, keys)))

        val comment = CommunityEventSchema.comment(workKeys, "x", null, 100)
        assertNull(CommunityEventSchema.parseComment(NostrKeys.sign(comment.copy(content = "a".repeat(4001)), keys)))
    }
}
