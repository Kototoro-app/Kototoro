package org.skepsun.kototoro.community.indexer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrKeys
import org.skepsun.kototoro.community.nostr.NostrPow
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CommunityAggregatorTest {

    private val day = CommunityAggregator.DAY
    private val now = 1_800_000_000L
    private val config = IndexerConfig(now = now, relays = listOf("wss://a"), powRating = 0, powComment = 0, minChartWeight = 1.0)

    private val onePiece = listOf("https://anilist.co/manga/30013", "kototoro:t:comic:onepiece")
    private val onePieceTitleOnly = listOf("kototoro:t:comic:onepiece")
    private val naruto = listOf("https://anilist.co/manga/30011")
    private val bleach = listOf("https://anilist.co/manga/30012")

    private val readers = List(6) { NostrKeyPair.generate() }

    private fun rate(who: NostrKeyPair, keys: List<String>, stars: Int, at: Long = now - day, nsfw: Boolean = false, title: String? = null) =
        NostrKeys.sign(CommunityEventSchema.rating(keys, "comic", "MANGA", stars, title, nsfw, at), who)

    private fun comment(who: NostrKeyPair, keys: List<String>, text: String, parent: NostrEvent? = null) =
        NostrKeys.sign(CommunityEventSchema.comment(keys, text, null, now - day, parent = parent), who)

    /** Archived long enough ago that every author counts in full, unless [seen] says otherwise. */
    private fun archive(vararg events: NostrEvent, seen: Long = now - 60 * day) = events.map { ArchivedEvent(it, seen) }

    private fun aggregate(events: List<ArchivedEvent>, moderation: CommunityModeration = CommunityModeration(), cfg: IndexerConfig = config) =
        CommunityAggregator.aggregate(events, moderation, cfg)

    @Test
    fun `ratings under different keys of one work add up, with a bayesian score and a distribution`() {
        val output = aggregate(archive(
            rate(readers[0], onePiece, 10, title = "One Piece"),
            rate(readers[1], onePieceTitleOnly, 8, title = "One Piece"),
            rate(readers[2], naruto, 4),
        ))
        val id = output.keys.getValue("kototoro:t:comic:onepiece")
        assertEquals("https://anilist.co/manga/30013", id)
        val work = output.works.getValue(id)
        assertEquals("One Piece", work.title)
        val rating = work.rating!!
        assertEquals(2, rating.count)
        assertEquals(9.0, rating.mean)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 1), rating.distribution)
        // The comic mean is (10 + 8 + 4) / 3; ten ratings of it pull the two real ones toward it.
        val prior = 22.0 / 3
        assertEquals(Math.round((18 + 10 * prior) / 12 * 100) / 100.0, rating.score)
        assertEquals(2, output.works.size)
        assertEquals(3, output.stats.ratings)
    }

    @Test
    fun `a reader's newer rating replaces the older one and a withdrawal or deletion removes it`() {
        val reader = readers[0]
        val first = rate(reader, naruto, 3, at = now - 3 * day)
        val second = rate(reader, naruto, 9, at = now - 2 * day)
        assertEquals(9.0, aggregate(archive(first, second)).works.values.single().rating!!.mean)

        val withdrawal = NostrKeys.sign(CommunityEventSchema.ratingWithdrawal(reader.publicKeyHex, "comic:${naruto[0]}", now - day), reader)
        assertTrue(aggregate(archive(first, second, withdrawal)).works.isEmpty())
        val later = rate(reader, naruto, 6, at = now)
        assertEquals(6.0, aggregate(archive(first, second, withdrawal, later)).works.values.single().rating!!.mean, "rating again after withdrawing counts")

        val own = NostrKeys.sign(CommunityEventSchema.deletion(second, now), reader)
        val foreign = NostrKeys.sign(CommunityEventSchema.deletion(second, now), readers[1])
        assertTrue(aggregate(archive(second, own)).works.isEmpty())
        assertEquals(1, aggregate(archive(second, foreign)).stats.ratings, "nobody deletes another reader's rating")
    }

    @Test
    fun `one reader counts once per work even when they rated it under two keys`() {
        val reader = readers[0]
        val output = aggregate(archive(
            rate(readers[1], onePiece, 7),
            rate(reader, onePiece, 2, at = now - 3 * day),
            rate(reader, onePieceTitleOnly, 10, at = now - day),
        ))
        val rating = output.works.values.single().rating!!
        assertEquals(2, rating.count)
        assertEquals(8.5, rating.mean, "the reader's newest rating stands")
    }

    @Test
    fun `new accounts weigh less, proof of work is enforced and a burst is voided`() {
        val fresh = archive(rate(readers[0], naruto, 10), seen = now - day)
        assertEquals(0.3, aggregate(fresh).works.values.single().rating!!.weight)

        val unmined = rate(readers[1], naruto, 10)
        assertTrue(aggregate(archive(unmined), cfg = config.copy(powRating = 8)).works.isEmpty())
        val mined = runBlocking {
            NostrKeys.sign(NostrPow.mine(CommunityEventSchema.rating(naruto, "comic", null, 10, null, false, now - day), readers[1].publicKeyHex, 8, CommunityEventSchema.POW_TAG), readers[1])
        }
        assertEquals(1, aggregate(archive(mined), cfg = config.copy(powRating = 8)).stats.ratings)

        val spammer = readers[2]
        val burst = (1..5).map { rate(spammer, listOf("https://anilist.co/manga/${100 + it}"), 10) }
        val output = aggregate(archive(*burst.toTypedArray(), rate(readers[3], naruto, 5)), cfg = config.copy(burstLimit = 3))
        assertEquals(1, output.stats.ratings, "five ratings in a day against a limit of three are all voided")
    }

    @Test
    fun `comments are counted and hidden by trusted reports, blocked words or the maintainer`() {
        val first = comment(readers[0], naruto, "Great series")
        val reply = comment(readers[1], naruto, "Agreed", parent = first)
        val spam = comment(readers[2], naruto, "Buy cheap watches")
        val reported = comment(readers[3], naruto, "Ugly words")
        val reports = (0..2).map { NostrKeys.sign(CommunityEventSchema.report(reported, CommunityReportType.PROFANITY, now), readers[it]) }
        val newReport = ArchivedEvent(NostrKeys.sign(CommunityEventSchema.report(first, CommunityReportType.SPAM, now), readers[4]), now)
        val output = aggregate(
            archive(first, reply, spam, reported, *reports.toTypedArray()) + newReport,
            CommunityModeration(blockedWords = listOf("CHEAP WATCHES")),
        )
        val work = output.works.values.single()
        assertEquals(2, work.comments)
        assertEquals(listOf(reported.id, spam.id).sorted(), work.hiddenComments)
        assertEquals(3, output.reports[reported.id])
        assertNull(output.reports[first.id], "a report from a day-old account does not count")
        assertEquals("comic", work.medium, "a work only commented on still gets its medium from its key")

        val blocked = aggregate(archive(first, reply), CommunityModeration(blockedPubkeys = listOf(readers[0].publicKeyHex)))
        assertEquals(listOf(first.id), blocked.works.values.single().hiddenComments)
    }

    @Test
    fun `charts keep works with enough weight, by window, medium and nsfw`() {
        val output = aggregate(archive(
            rate(readers[0], naruto, 9, at = now - 2 * day),
            rate(readers[1], naruto, 9, at = now - 2 * day),
            rate(readers[0], bleach, 6, at = now - 20 * day),
            rate(readers[1], bleach, 6, at = now - 20 * day),
            rate(readers[2], onePiece, 10, nsfw = true),
        ), cfg = config.copy(minChartWeight = 2.0))
        fun chart(window: String, nsfw: Boolean = false) = output.charts.single { it.window == window && it.medium == "comic" && it.nsfw == nsfw }
        assertEquals(listOf(naruto[0]), chart("week").entries.map { it.id })
        assertEquals(listOf(naruto[0], bleach[0]), chart("month").entries.map { it.id })
        assertTrue(chart("all", nsfw = true).entries.isEmpty(), "one rating is below the chart weight")
        assertEquals(24, output.charts.size)
        assertTrue(output.works.getValue(onePiece[0]).nsfw)
    }

    @Test
    fun `works rated alike by the same readers are similar`() {
        val works = (1..4).map { listOf("https://anilist.co/manga/$it") }
        val events = readers.take(5).flatMapIndexed { i, reader ->
            val high = 6 + i % 3
            listOf(rate(reader, works[0], high + 2), rate(reader, works[1], high + 1), rate(reader, works[2], high - 3), rate(reader, works[3], high - 4))
        }
        val output = aggregate(archive(*events.toTypedArray()))
        val similar = output.works.getValue(works[0][0]).similar
        assertEquals(works[1][0], similar.first().id)
        assertEquals(5, similar.first().coRaters)
        assertFalse(similar.any { it.id == works[3][0] }, "opposite tastes are not similar")
    }

    @Test
    fun `the archive keeps the first sighting, drops forged lines and rewrites only touched months`(@TempDir dir: File) {
        val a = rate(readers[0], naruto, 5)
        val b = rate(readers[1], naruto, 6)
        assertEquals(1, IndexerArchive.append(dir, emptyList(), listOf(ArchivedEvent(a, now))))
        val loaded = IndexerArchive.load(dir)
        assertEquals(0, IndexerArchive.append(dir, loaded, listOf(ArchivedEvent(a, now + day))))
        assertEquals(1, IndexerArchive.append(dir, loaded, listOf(ArchivedEvent(b, now + 40 * day))))
        val all = IndexerArchive.load(dir)
        assertEquals(mapOf(a.id to now, b.id to now + 40 * day), all.associate { it.event.id to it.seen })
        assertEquals(2, File(dir, "events").listFiles()!!.size)

        val month = File(dir, "events").listFiles()!!.minBy { it.name }
        val forged = a.copy(content = "forged").toJson().toString()
        GZIPOutputStream(month.outputStream()).bufferedWriter().use { it.write("{\"seen\":$now,\"event\":$forged}\n") }
        assertEquals(listOf(b.id), IndexerArchive.load(dir).map { it.event.id })
        assertTrue(GZIPInputStream(month.inputStream()).bufferedReader().readText().contains("forged"))
    }

    @Test
    fun `the snapshot puts every key and work where the shard function says and lists every file`(@TempDir dir: File) {
        val output = aggregate(archive(rate(readers[0], onePiece, 8), comment(readers[1], naruto, "Hi")))
        File(dir, "v1/works/stale.json").apply { parentFile.mkdirs(); writeText("{}") }
        val manifest = SnapshotWriter.write(dir, output, config)
        assertFalse(File(dir, "v1/works/stale.json").exists(), "a rebuild leaves nothing from the last one")
        for (key in onePiece) {
            val shard = File(dir, CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key)))
            assertEquals(onePiece[0], Json.parseToJsonElement(shard.readText()).jsonObject.getValue(key).jsonPrimitive.content)
        }
        val works = File(dir, CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(naruto[0])))
        assertEquals(1, Json.parseToJsonElement(works.readText()).jsonObject.getValue(naruto[0]).jsonObject.getValue("comments").jsonPrimitive.content.toInt())
        val written = File(dir, "v1").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()
        assertEquals(written - CommunitySnapshotShards.MANIFEST_PATH, manifest.files.keys)
        val read = Json.decodeFromString(CommunitySnapshotManifest.serializer(), File(dir, CommunitySnapshotShards.MANIFEST_PATH).readText())
        assertEquals(manifest, read)
    }
}
