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

    private fun decodeSingle(manga: MihonBackupManga): ExternalBackupContentRecord {
        val payload = decodeMihonOrAniyomiBackup(encode(MihonBackup(backupManga = listOf(manga))), ExternalBackupApp.MIHON)
        return payload!!.records.single()
    }

    @Test
    fun `titles read without history restore the furthest read chapter`() {
        val record = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                dateAdded = 1_700_000_000_000L,
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/3", chapterNumber = 3f, sourceOrder = 0),
                    MihonBackupChapter(url = "/ch/2b", read = true, lastPageRead = 4, chapterNumber = 2f, sourceOrder = 1),
                    MihonBackupChapter(url = "/ch/2a", read = true, chapterNumber = 2f, sourceOrder = 2),
                    MihonBackupChapter(url = "/ch/1", read = true, chapterNumber = 1f, sourceOrder = 3),
                ),
            ),
        )
        assertEquals("/ch/2b", record.historyChapterUrl)
        assertEquals(1_700_000_000_000L, record.historyTimestamp)
        assertEquals(4, record.historyPage)
    }

    @Test
    fun `unnumbered read chapters fall back to the newest by source order`() {
        val record = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/extra", sourceOrder = 0),
                    MihonBackupChapter(url = "/oneshot-2", read = true, sourceOrder = 1),
                    MihonBackupChapter(url = "/oneshot-1", read = true, sourceOrder = 2),
                ),
            ),
        )
        assertEquals("/oneshot-2", record.historyChapterUrl)
        assertEquals(1L, record.historyTimestamp)
    }

    @Test
    fun `a history entry on an unread chapter wins over read chapters and unread titles get none`() {
        val withHistory = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/2", read = true, chapterNumber = 2f),
                    MihonBackupChapter(url = "/ch/1", read = false, lastPageRead = 17, chapterNumber = 1f),
                ),
                history = listOf(MihonBackupHistory(url = "/ch/1", lastRead = 1_700_000_500_000L)),
            ),
        )
        assertEquals("/ch/1", withHistory.historyChapterUrl)
        assertEquals(1_700_000_500_000L, withHistory.historyTimestamp)
        assertEquals(17, withHistory.historyPage)

        val unread = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(MihonBackupChapter(url = "/ch/1", chapterNumber = 1f)),
            ),
        )
        assertNull(unread.historyChapterUrl)
        assertNull(unread.historyTimestamp)
        assertEquals(0, unread.historyPage)
    }

    @Test
    fun `a read history chapter gives way to a later chapter marked read without being opened`() {
        val record = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/251", read = true, chapterNumber = 251f, sourceOrder = 0),
                    MihonBackupChapter(url = "/ch/250", read = true, chapterNumber = 250f, sourceOrder = 1),
                    MihonBackupChapter(url = "/ch/249", read = true, lastPageRead = 30, chapterNumber = 249f, sourceOrder = 2),
                ),
                history = listOf(MihonBackupHistory(url = "/ch/249", lastRead = 1_700_000_500_000L)),
            ),
        )
        assertEquals("/ch/251", record.historyChapterUrl)
        assertEquals(1_700_000_500_000L, record.historyTimestamp)
    }

    @Test
    fun `a read history chapter stays when nothing later is read and unnumbered chapters use source order`() {
        val latest = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/3", chapterNumber = 3f, sourceOrder = 0),
                    MihonBackupChapter(url = "/ch/2", read = true, lastPageRead = 9, chapterNumber = 2f, sourceOrder = 1),
                    MihonBackupChapter(url = "/ch/1", read = true, chapterNumber = 1f, sourceOrder = 2),
                ),
                history = listOf(MihonBackupHistory(url = "/ch/2", lastRead = 1_700_000_500_000L)),
            ),
        )
        assertEquals("/ch/2", latest.historyChapterUrl)
        assertEquals(9, latest.historyPage)

        val unnumbered = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/extra-2", read = true, sourceOrder = 0),
                    MihonBackupChapter(url = "/extra-1", read = true, sourceOrder = 1),
                ),
                history = listOf(MihonBackupHistory(url = "/extra-1", lastRead = 1_700_000_500_000L)),
            ),
        )
        assertEquals("/extra-2", unnumbered.historyChapterUrl)
    }

    @Test
    fun `a history chapter missing from the backup falls back to the furthest read chapter`() {
        val record = decodeSingle(
            MihonBackupManga(
                source = mangaDexId,
                url = "/manga/42",
                chapters = listOf(
                    MihonBackupChapter(url = "/ch/2", read = true, chapterNumber = 2f, sourceOrder = 0),
                    MihonBackupChapter(url = "/ch/1", read = true, chapterNumber = 1f, sourceOrder = 1),
                ),
                history = listOf(MihonBackupHistory(url = "/gone", lastRead = 1_700_000_500_000L)),
            ),
        )
        assertEquals("/ch/2", record.historyChapterUrl)
        assertEquals(1_700_000_500_000L, record.historyTimestamp)
    }

    @Test
    fun `streaming decoder matches ByteArray decoding for raw and gzipped Mihon and Aniyomi backups`() {
        val mihon = mihonBackup()
        val mihonBytes = encode(mihon)
        val expectedMihon = decodeMihonOrAniyomiBackup(mihonBytes, ExternalBackupApp.MIHON)
        assertNotNull(expectedMihon)
        assertEquals(expectedMihon, decodeMihonOrAniyomiBackup(Buffer().write(mihonBytes), ExternalBackupApp.MIHON))
        assertEquals(expectedMihon, decodeMihonOrAniyomiBackup(Buffer().write(gzip(mihonBytes)), ExternalBackupApp.MIHON))

        val autoMihon = decodeMihonOrAniyomiBackupAutoDetect(Buffer().write(gzip(mihonBytes)))
        assertEquals(DecodedExternalBackup(ExternalBackupApp.MIHON, expectedMihon!!), autoMihon)

        val aniyomi = AniyomiBackup(
            backupManga = listOf(AniyomiBackupManga(source = 11, url = "/m", title = "Manga")),
            backupCategories = listOf(MihonBackupCategory(name = "Anime", order = 2, id = 9)),
            backupAnime = listOf(
                AniyomiBackupAnime(
                    source = 12,
                    url = "/a",
                    title = "Anime",
                    episodes = listOf(
                        AniyomiBackupEpisode(url = "/ep/1", seen = true),
                        AniyomiBackupEpisode(url = "/ep/2", seen = false),
                    ),
                ),
            ),
            backupSources = listOf(
                MihonBackupSource(name = "Manga source", sourceId = 11),
                MihonBackupSource(name = "Overridden source", sourceId = 12),
            ),
            backupAnimeSources = listOf(MihonBackupSource(name = "Anime source", sourceId = 12)),
        )
        val aniyomiBytes = ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), aniyomi)
        val expectedAniyomi = decodeMihonOrAniyomiBackup(aniyomiBytes, ExternalBackupApp.ANIYOMI)
        assertNotNull(expectedAniyomi)
        assertEquals(expectedAniyomi, decodeMihonOrAniyomiBackup(Buffer().write(aniyomiBytes), ExternalBackupApp.ANIYOMI))
        assertEquals(expectedAniyomi, decodeMihonOrAniyomiBackup(Buffer().write(gzip(aniyomiBytes)), ExternalBackupApp.ANIYOMI))

        val autoAniyomi = decodeMihonOrAniyomiBackupAutoDetect(Buffer().write(gzip(aniyomiBytes)))
        assertEquals(DecodedExternalBackup(ExternalBackupApp.ANIYOMI, expectedAniyomi!!), autoAniyomi)
    }

    @Test
    fun `streaming decoder skips unknown protobuf wire types and preserves interleaved Aniyomi order`() {
        val mangaBytes = ProtoBuf.encodeToByteArray(
            AniyomiBackupManga.serializer(),
            AniyomiBackupManga(source = 11, url = "/m/1", title = "Interleaved Manga"),
        )
        val animeBytes = ProtoBuf.encodeToByteArray(
            AniyomiBackupAnime.serializer(),
            AniyomiBackupAnime(source = 12, url = "/a/1", title = "Interleaved Anime"),
        )
        val sourceMangaBytes = ProtoBuf.encodeToByteArray(
            MihonBackupSource.serializer(),
            MihonBackupSource(name = "Source Eleven", sourceId = 11),
        )
        val sourceAnimeBytes = ProtoBuf.encodeToByteArray(
            MihonBackupSource.serializer(),
            MihonBackupSource(name = "Source Twelve", sourceId = 12),
        )

        val wire = Buffer().apply {
            // Unknown varint (field 99, wire type 0)
            writeVarint((99L shl 3) or 0L)
            writeVarint(123456789L)
            // Unknown 64-bit fixed (field 98, wire type 1)
            writeVarint((98L shl 3) or 1L)
            writeLongLe(0x0102030405060708L)
            // Unknown 32-bit fixed (field 97, wire type 5)
            writeVarint((97L shl 3) or 5L)
            writeIntLe(0x11223344)
            // Unknown deprecated group (field 96, wire type 3 ... wire type 4)
            writeVarint((96L shl 3) or 3L)
            writeVarint((1L shl 3) or 0L)
            writeVarint(42L)
            writeVarint((96L shl 3) or 4L)
            // Unknown length-delimited blob (field 104, wire type 2)
            writeVarint((104L shl 3) or 2L)
            writeVarint(4L)
            writeUtf8("pref")
            // Anime written BEFORE manga, and sources written LAST
            writeVarint((501L shl 3) or 2L)
            writeVarint(animeBytes.size.toLong())
            write(animeBytes)
            writeVarint((1L shl 3) or 2L)
            writeVarint(mangaBytes.size.toLong())
            write(mangaBytes)
            writeVarint((101L shl 3) or 2L)
            writeVarint(sourceMangaBytes.size.toLong())
            write(sourceMangaBytes)
            writeVarint((103L shl 3) or 2L)
            writeVarint(sourceAnimeBytes.size.toLong())
            write(sourceAnimeBytes)
        }.readByteArray()

        val payload = decodeMihonOrAniyomiBackup(Buffer().write(gzip(wire)), ExternalBackupApp.ANIYOMI)
        assertNotNull(payload)
        assertEquals(listOf("Interleaved Manga", "Interleaved Anime"), payload!!.records.map { it.title })
        assertEquals(listOf("Source Eleven", "Source Twelve"), payload.records.map { it.sourceDisplayName })
    }

    @Test
    fun `readExternalBackupBytes enforces exact byte ceiling and stops gzip bombs early`() {
        val raw = encode(mihonBackup())
        assertArrayEquals(raw, readExternalBackupBytes(Buffer().write(raw), maxBytes = raw.size.toLong()))

        val overLimitSource = CloseTrackingSource(raw)
        assertThrows(ExternalBackupTooLargeException::class.java) {
            readExternalBackupBytes(overLimitSource.buffer(), maxBytes = raw.size.toLong() - 1L)
        }
        assertTrue(overLimitSource.closed)

        // 8 MiB of zeros gzips down to ~8 KiB; reading with a 32 KiB decompressed cap must fail fast.
        val gzipBomb = gzipZeros(8 * 1024 * 1024)
        val bombSource = CloseTrackingSource(gzipBomb)
        assertThrows(ExternalBackupTooLargeException::class.java) {
            readExternalBackupBytes(bombSource.buffer(), maxBytes = 32L * 1024L)
        }
        assertTrue(bombSource.closed)
    }

    @Test
    fun `streaming decoder fails fast on gzip bombs and oversized protobuf records`() {
        // 8 MiB unknown protobuf field compressed to ~8 KiB; streaming skip must trip maxBytes immediately.
        val gzipProtobufBomb = gzipUnknownFieldBlob(8 * 1024 * 1024)
        val bombSource = CloseTrackingSource(gzipProtobufBomb)
        assertThrows(ExternalBackupTooLargeException::class.java) {
            decodeMihonOrAniyomiBackup(
                source = bombSource.buffer(),
                app = ExternalBackupApp.MIHON,
                maxBytes = 32L * 1024L,
            )
        }
        assertTrue(bombSource.closed)

        // All-zero gzip bomb fails on the very first invalid tag byte without decompressing the rest.
        val zeroBombSource = CloseTrackingSource(gzipZeros(8 * 1024 * 1024))
        assertNull(
            decodeMihonOrAniyomiBackup(
                source = zeroBombSource.buffer(),
                app = ExternalBackupApp.MIHON,
                maxBytes = 32L * 1024L,
            ),
        )
        assertTrue(zeroBombSource.closed)

        // Forged 1 GiB length-delimited record header in only 6 bytes must fail immediately without allocation.
        val forgedOversizedRecord = Buffer().apply {
            writeVarint((1L shl 3) or 2L)
            writeVarint(1024L * 1024L * 1024L)
        }.readByteArray()
        val forgedSource = CloseTrackingSource(forgedOversizedRecord)
        assertThrows(ExternalBackupTooLargeException::class.java) {
            decodeMihonOrAniyomiBackup(
                source = forgedSource.buffer(),
                app = ExternalBackupApp.MIHON,
                maxRecordBytes = 1024L,
            )
        }
        assertTrue(forgedSource.closed)

        // Real backup record exceeding a tight per-record cap also fails fast with ExternalBackupTooLargeException.
        val validBytes = encode(mihonBackup())
        val cappedSource = CloseTrackingSource(validBytes)
        assertThrows(ExternalBackupTooLargeException::class.java) {
            decodeMihonOrAniyomiBackup(
                source = cappedSource.buffer(),
                app = ExternalBackupApp.MIHON,
                maxRecordBytes = 16L,
            )
        }
        assertTrue(cappedSource.closed)
    }

    @Test
    fun `streaming decoder rejects malformed protobuf wire records cleanly and closes input`() {
        val malformedInputs = listOf(
            // Field 1, length 127, only 2 bytes follow
            byteArrayOf(0x0A, 0x7F, 0x01, 0x02),
            // Invalid wire type 6 on field 1
            byteArrayOf(0x0E, 0x00),
            // Invalid field number 0
            byteArrayOf(0x00, 0x00),
            // Unexpected top-level end-group (wire type 4)
            byteArrayOf(0x0C, 0x00),
            // Wrong wire type (varint instead of length-delimited) for known field 1
            byteArrayOf(0x08, 0x01),
            // Malformed varint exceeding 10 bytes
            ByteArray(12) { 0x80.toByte() },
            // Unterminated unknown start-group
            byteArrayOf(((96 shl 3) or 3).toByte(), 0x00),
            // Empty length-delimited MihonBackupManga missing required source & url fields
            byteArrayOf(0x0A, 0x00),
        )
        malformedInputs.forEach { bad ->
            val source = CloseTrackingSource(bad)
            assertNull(decodeMihonOrAniyomiBackup(source.buffer(), ExternalBackupApp.MIHON))
            assertTrue(source.closed)
        }

        // Venera closes the source and returns null
        val veneraSource = CloseTrackingSource(encode(mihonBackup()))
        assertNull(decodeMihonOrAniyomiBackup(veneraSource.buffer(), ExternalBackupApp.VENERA))
        assertTrue(veneraSource.closed)

        // JSON backup still throws UnsupportedExternalBackupException and closes source
        val jsonSource = CloseTrackingSource("""{"backupManga":[]}""".encodeToByteArray())
        assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeMihonOrAniyomiBackup(jsonSource.buffer(), ExternalBackupApp.MIHON)
        }
        assertTrue(jsonSource.closed)
    }

    @Test
    fun `streaming decoder handles multi-thousand-entry gzipped Mihon backup with 100k chapters`() {
        val mangaCount = 2_000
        val chaptersPerManga = 50
        val compressed = Buffer()
        (compressed as Sink).gzip().buffer().use { gzipOut ->
            for (i in 0 until mangaCount) {
                val chapters = List(chaptersPerManga) { ch ->
                    MihonBackupChapter(
                        url = "/m/$i/ch/$ch",
                        name = "Chapter ${ch + 1}",
                        read = ch < 25,
                        lastPageRead = if (ch == 24) 7L else 0L,
                        chapterNumber = (ch + 1).toFloat(),
                        sourceOrder = (chaptersPerManga - 1 - ch).toLong(),
                    )
                }
                val manga = MihonBackupManga(
                    source = mangaDexId,
                    url = "/manga/$i",
                    title = "Series $i",
                    dateAdded = 1_700_000_000_000L + i,
                    chapters = chapters,
                    categories = listOf(1L),
                )
                val recordBytes = ProtoBuf.encodeToByteArray(MihonBackupManga.serializer(), manga)
                gzipOut.writeVarint((1L shl 3) or 2L)
                gzipOut.writeVarint(recordBytes.size.toLong())
                gzipOut.write(recordBytes)
            }
            val categoryBytes = ProtoBuf.encodeToByteArray(
                MihonBackupCategory.serializer(),
                MihonBackupCategory(name = "Reading", order = 1, id = 1),
            )
            gzipOut.writeVarint((2L shl 3) or 2L)
            gzipOut.writeVarint(categoryBytes.size.toLong())
            gzipOut.write(categoryBytes)

            val sourceBytes = ProtoBuf.encodeToByteArray(
                MihonBackupSource.serializer(),
                MihonBackupSource(name = "MangaDex", sourceId = mangaDexId),
            )
            gzipOut.writeVarint((101L shl 3) or 2L)
            gzipOut.writeVarint(sourceBytes.size.toLong())
            gzipOut.write(sourceBytes)
        }

        val payload = decodeMihonOrAniyomiBackup(compressed, ExternalBackupApp.MIHON)
        assertNotNull(payload)
        assertEquals(mangaCount, payload!!.records.size)
        assertEquals("Reading", payload.favoriteCategories.single().name)
        val first = payload.records.first()
        val last = payload.records.last()
        assertEquals("Series 0", first.title)
        assertEquals("MangaDex", first.sourceDisplayName)
        assertEquals(chaptersPerManga, first.chaptersCount)
        assertEquals(25, first.readEntriesCount)
        assertEquals(0.5f, first.progressPercent)
        assertEquals("/m/0/ch/24", first.historyChapterUrl)
        assertEquals(7, first.historyPage)
        assertEquals("Series ${mangaCount - 1}", last.title)
        assertEquals("MangaDex", last.sourceDisplayName)
    }

    private fun gzipZeros(uncompressedBytes: Int): ByteArray {
        val out = Buffer()
        val chunk = ByteArray(64 * 1024)
        (out as Sink).gzip().buffer().use { sink ->
            var remaining = uncompressedBytes
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size)
                sink.write(chunk, 0, n)
                remaining -= n
            }
        }
        return out.readByteArray()
    }

    private fun gzipUnknownFieldBlob(payloadBytes: Int): ByteArray {
        val out = Buffer()
        val chunk = ByteArray(64 * 1024)
        (out as Sink).gzip().buffer().use { sink ->
            sink.writeVarint((99L shl 3) or 2L)
            sink.writeVarint(payloadBytes.toLong())
            var remaining = payloadBytes
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size)
                sink.write(chunk, 0, n)
                remaining -= n
            }
        }
        return out.readByteArray()
    }

    private fun okio.BufferedSink.writeVarint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            writeByte(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        writeByte(v.toInt())
    }
}
