package org.skepsun.kototoro.backups.external

import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.ForwardingSource
import okio.Sink
import okio.buffer
import okio.gzip
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.IOException

/**
 * The pure half of the external backup decoder: what an iOS app runs to restore a Mihon/Aniyomi backup.
 * Backups are built with the same protobuf models, gzipped like the real thing, and read back.
 */
class ExternalBackupDecodingTest {

    private val mangaDexId = 2499283573021220255L

    private fun mihonBackup() = MihonBackup(
        backupManga = listOf(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                title = "Example",
                author = "Someone",
                genre = listOf("Action", "Drama"),
                dateAdded = 1_700_000_000_000L,
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/1", name = "One", read = true),
                    MihonBackupChapter(url = "/ch/2", name = "Two", read = false),
                ),
                categories = listOf(7L),
                history = listOf(MihonBackupHistory(url = "/ch/1", lastRead = 1_700_000_500_000L)),
            ),
        ),
        backupCategories = listOf(MihonBackupCategory(name = "Reading", order = 1, id = 7)),
        backupSources = listOf(MihonBackupSource(name = "MangaDex", sourceId = mangaDexId)),
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = Buffer()
        val sink = (out as Sink).gzip().buffer() // Buffer is both a Source and a Sink
        sink.write(bytes)
        sink.close()
        return out.readByteArray()
    }

    private fun encode(backup: MihonBackup) = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)

    @Test
    fun `a gzipped Mihon backup decodes into content and category records`() {
        val bytes = readExternalBackupBytes(Buffer().write(gzip(encode(mihonBackup()))))
        val payload = decodeMihonOrAniyomiBackup(bytes, ExternalBackupApp.MIHON)
        assertNotNull(payload)

        val record = payload!!.records.single()
        assertEquals("MIHON_$mangaDexId", record.sourceName)
        assertEquals("MangaDex", record.sourceDisplayName)
        assertEquals(ContentType.MANGA, record.contentType)
        assertEquals("/manga/42", record.url)
        assertEquals("Example", record.title)
        assertEquals(2, record.chaptersCount)
        assertEquals(1, record.readEntriesCount)
        assertEquals(0.5f, record.progressPercent)
        assertEquals("/ch/1", record.historyChapterUrl)
        assertEquals(1_700_000_500_000L, record.historyTimestamp)

        val category = payload.favoriteCategories.single()
        assertEquals("Reading", category.name)
        assertEquals(7L, category.id)
    }

    @Test
    fun `an uncompressed backup is passed through unchanged`() {
        val raw = encode(mihonBackup())
        assertArrayEquals(raw, readExternalBackupBytes(Buffer().write(raw)))
    }

    @Test
    fun `JSON backups are rejected instead of being misread as protobuf`() {
        val json = """{"backupManga":[]}""".encodeToByteArray()
        assertThrows(UnsupportedExternalBackupException::class.java) { readExternalBackupBytes(Buffer().write(json)) }
    }

    @Test
    fun `bytes that are not a backup decode to null`() {
        val malformed = byteArrayOf(0x0A, 0x7F, 0x01, 0x02) // field 1, length 127, but only 2 bytes follow
        assertNull(decodeMihonOrAniyomiBackup(malformed, ExternalBackupApp.MIHON))
        assertNull(decodeMihonOrAniyomiBackup(malformed, ExternalBackupApp.ANIYOMI))
    }

    @Test
    fun `Venera backups are SQLite based and have no shared decoder`() {
        assertNull(decodeMihonOrAniyomiBackup(encode(mihonBackup()), ExternalBackupApp.VENERA))
    }

    @Test
    fun `timestamp and progress helpers keep their legacy semantics`() {
        assertEquals(0L, normalizeTimestamp(0))
        assertEquals(1_000_000_000_000L, normalizeTimestamp(1_000_000_000L)) // seconds are promoted to milliseconds
        assertEquals(1_700_000_000_000L, normalizeTimestamp(1_700_000_000_000L))
        assertNull(calculateProgressPercent(totalCount = 0, completedCount = 3))
        assertEquals(1f, calculateProgressPercent(totalCount = 2, completedCount = 9)) // clamped to the total
        assertEquals(0f, calculateProgressPercent(totalCount = 2, completedCount = -1))
        assertEquals(1_000_000_000_000L, resolveFavoriteTimestamp(1_100_000_000L, 1_000_000_000L, 0L))
        assertEquals(1_100_000_000_000L, resolveFavoriteTimestamp(1_100_000_000L, 0L, 1_200_000_000L))
        assertEquals(1_200_000_000_000L, resolveFavoriteTimestamp(null, -1L, 1_200_000_000L))
        assertNull(resolveFavoriteTimestamp(null, 0L, -1L))
    }

    @Test
    fun `a fixed wire fixture preserves Aniyomi anime field and source registry`() {
        // Hand-written protobuf: anime field 501, anime-source registry 103. This fixture is independent
        // of our serializers, so changing ProtoNumber annotations cannot silently change both sides.
        val bytes = (
            "aa1f1f080912022f611a05416e696d658201060a022f3120018201060a022f322000" +
                "ba06090a05566964656f1009"
        ).hexToByteArray()
        val payload = decodeMihonOrAniyomiBackup(bytes, ExternalBackupApp.ANIYOMI)
        assertNotNull(payload)
        val record = payload!!.records.single()
        assertEquals("ANIYOMI_9", record.sourceName)
        assertEquals("Video", record.sourceDisplayName)
        assertEquals(ContentType.VIDEO, record.contentType)
        assertEquals("Anime", record.title)
        assertEquals("/a", record.url)
        assertEquals(2, record.chaptersCount)
        assertEquals(1, record.readEntriesCount)
        assertEquals(0.5f, record.progressPercent)
    }

    @Test
    fun `Aniyomi restores manga and history-only anime and skips inactive entries`() {
        val backup = AniyomiBackup(
            backupManga = listOf(AniyomiBackupManga(source = 11, url = "/m", title = "Manga")),
            backupAnime = listOf(
                AniyomiBackupAnime(
                    source = 12,
                    url = "/a",
                    favorite = false,
                    history = listOf(
                        AniyomiBackupHistory(url = "/old", lastRead = 100),
                        AniyomiBackupHistory(url = "/new", lastRead = 200),
                    ),
                ),
                AniyomiBackupAnime(source = 12, url = "/ignored", favorite = false),
            ),
            backupSources = listOf(MihonBackupSource(name = "Manga source", sourceId = 11)),
            backupAnimeSources = listOf(MihonBackupSource(name = "Anime source", sourceId = 12)),
        )
        val bytes = ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), backup)
        val payload = decodeMihonOrAniyomiBackup(
            readExternalBackupBytes(Buffer().write(gzip(bytes))),
            ExternalBackupApp.ANIYOMI,
        )
        assertNotNull(payload)
        val records = payload!!.records
        assertEquals(listOf("MIHON_11", "ANIYOMI_12"), records.map { it.sourceName })
        assertEquals(listOf("Manga source", "Anime source"), records.map { it.sourceDisplayName })
        assertEquals(listOf(ContentType.MANGA, ContentType.VIDEO), records.map { it.contentType })
        assertEquals(false, records.last().isFavorite)
        assertEquals("/new", records.last().historyChapterUrl)
        assertEquals(200L, records.last().historyTimestamp)
        assertNull(records.last().progressPercent)
    }

    @Test
    fun `input is closed after successful reads and after header or gzip failures`() {
        val valid = encode(mihonBackup())
        listOf(valid, gzip(valid)).forEach { bytes ->
            val source = CloseTrackingSource(bytes)
            assertArrayEquals(valid, readExternalBackupBytes(source.buffer()))
            assertTrue(source.closed)
        }
        listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(0x1f, 0x8b.toByte())).forEach { bytes ->
            val source = CloseTrackingSource(bytes)
            assertThrows(IOException::class.java) { readExternalBackupBytes(source.buffer()) }
            assertTrue(source.closed)
        }
        listOf("{}", "{\"backup\":[]}", "{\n}").forEach { text ->
            val source = CloseTrackingSource(text.encodeToByteArray())
            assertThrows(UnsupportedExternalBackupException::class.java) {
                readExternalBackupBytes(source.buffer())
            }
            assertTrue(source.closed)
        }
    }

    private class CloseTrackingSource(bytes: ByteArray) : ForwardingSource(Buffer().write(bytes)) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
