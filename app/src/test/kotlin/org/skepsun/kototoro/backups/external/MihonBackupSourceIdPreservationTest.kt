package org.skepsun.kototoro.backups.external

import kotlinx.serialization.protobuf.ProtoBuf
import org.skepsun.kototoro.parsers.model.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the exact behaviour of importing a Tachiyomi-family (Mihon / TachiyomiSY)
 * protobuf backup: the per-manga `source` id (proto field 1, int64) must survive
 * decode and be carried into the Kototoro source key as `MIHON_{id}`.
 *
 * Decoder output retains the original id. Source resolution then converts only known
 * non-installable ExHentai built-ins to Kototoro's `EXHENTAI` JAR source; other ids remain
 * ordinary Mihon keys for extension recovery.
 */
class MihonBackupSourceIdPreservationTest {

    @Test
    fun `TachiyomiSY style backup preserves built-in EXH source id 6902`() {
        val encoded = encodeTachiyomiSyBackup(source = 6902L, url = "/g/123456/abc/")

        val decoded = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), encoded)

        assertEquals(1, decoded.backupManga.size)
        val manga = decoded.backupManga.first()
        assertEquals(6902L, manga.source)
        assertEquals("/g/123456/abc/", manga.url)
        assertEquals("Some Doujinshi", manga.title)
        assertTrue(manga.favorite)
        assertEquals(1, manga.chapters.size)

        // This is the exact key the import pipeline writes into MangaEntity.source
        // (ExternalBackupDecoder.MihonBackup.toPayload -> "MIHON_${manga.source}").
        assertEquals("MIHON_6902", "MIHON_${manga.source}")
    }

    @Test
    fun `decoder preserves Komikku and Neko built-in ids before source resolution`() {
        val sourceIds = listOf(
            6_225_928_719_850_211_219L,
            2_499_283_573_021_220_255L,
        )

        sourceIds.forEach { sourceId ->
            val encoded = encodeTachiyomiSyBackup(source = sourceId, url = "/title/example")
            val manga = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), encoded).backupManga.first()

            assertEquals(sourceId, manga.source)
            assertEquals("MIHON_$sourceId", "MIHON_${manga.source}")
        }
    }

    @Test
    fun `non-installable fork ExHentai ids target the Kototoro ExHentai source`() {
        assertEquals(NonInstallableForkTarget("EXHENTAI", ContentType.HENTAI_MANGA), targetForNonInstallableForkSource(6902L))
        assertEquals(NonInstallableForkTarget("EXHENTAI", ContentType.HENTAI_MANGA), targetForNonInstallableForkSource(6_225_928_719_850_211_219L))
        assertEquals(null, targetForNonInstallableForkSource(6901L))
        assertEquals(null, targetForNonInstallableForkSource(1_713_178_126_840_476_467L))
        assertEquals(null, targetForNonInstallableForkSource(2_499_283_573_021_220_255L))
    }

    @Test
    fun `regular Mihon extension id survives round trip`() {
        val encoded = encodeTachiyomiSyBackup(source = 8_392_556_173_882_593_881L, url = "/manga/title")

        val decoded = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), encoded)

        assertEquals(8_392_556_173_882_593_881L, decoded.backupManga.first().source)
        assertEquals("MIHON_8392556173882593881", "MIHON_${decoded.backupManga.first().source}")
    }

    /**
     * Emits the exact protobuf a TachiyomiSY / Mihon backup writes for one manga.
     * Field numbers mirror eu.kanade.tachiyomi.data.backup.models.BackupManga.
     */
    private fun encodeTachiyomiSyBackup(source: Long, url: String): ByteArray {
        val backup = MihonBackup(
            backupManga = listOf(
                MihonBackupManga(
                    source = source,
                    url = url,
                    title = "Some Doujinshi",
                    artist = null,
                    author = null,
                    description = null,
                    genre = listOf("doujinshi"),
                    status = 1,
                    thumbnailUrl = "https://example.com/cover.jpg",
                    dateAdded = 1_700_000_000_000L,
                    favorite = true,
                    chapters = listOf(MihonBackupChapter(url = "/g/123456/abc/1/", name = "Chapter 1", read = true)),
                    categories = emptyList(),
                    history = listOf(MihonBackupHistory(url = "/g/123456/abc/1/", lastRead = 1_700_000_000_001L)),
                    lastModifiedAt = 0L,
                    favoriteModifiedAt = null,
                ),
            ),
            backupCategories = emptyList(),
        )
        return ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)
    }
}
