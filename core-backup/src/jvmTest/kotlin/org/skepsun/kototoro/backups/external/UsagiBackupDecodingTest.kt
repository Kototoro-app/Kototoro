package org.skepsun.kototoro.backups.external

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Usagi import decoding, checked against the exact JSON `UsagiBackupExportRepository` writes:
 * a ZIP of JSON arrays named `index`, `categories`, `favourites`, `history`, `bookmarks`,
 * `sources`, `scrobbling`, `statistics`.
 */
class UsagiBackupDecodingTest {

    /** Same Json settings as `UsagiBackupExportRepository`. */
    private val exportJson = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun indexJson(appId: String = USAGI_BACKUP_APP_ID): String = exportJson.encodeToString(
        ListSerializer(UsagiBackupIndex.serializer()),
        listOf(UsagiBackupIndex(appId = appId, createdAt = 1_700_000_000_000L)),
    )

    private fun <T> arrayJson(serializer: kotlinx.serialization.KSerializer<T>, items: List<T>): String =
        exportJson.encodeToString(ListSerializer(serializer), items)

    private fun manga(
        id: Long,
        title: String = "Manga $id",
        source: String = "MANGA_DEX",
        tags: Set<UsagiTagBackup> = emptySet(),
    ) = UsagiMangaBackup(
        id = id,
        title = title,
        url = "/manga/$id",
        publicUrl = "https://example.org/manga/$id",
        rating = -1f,
        coverUrl = "https://example.org/cover/$id.jpg",
        state = "ONGOING",
        authors = "Author $id",
        source = source,
        tags = tags,
    )

    private fun tag(title: String) = UsagiTagBackup(
        id = title.hashCode().toLong(),
        title = title,
        key = title.lowercase(),
        source = "MANGA_DEX",
    )

    private fun sampleEntries(): UsagiRawBackupEntries {
        val categories = listOf(
            UsagiCategoryBackup(categoryId = 11, createdAt = 10L, sortKey = 0, title = "Reading"),
            UsagiCategoryBackup(categoryId = 12, createdAt = 11L, sortKey = 1, title = "Later"),
        )
        val favourites = listOf(
            UsagiFavouriteBackup(
                mangaId = 1L,
                categoryId = 11L,
                createdAt = 1_700_000_100_000L,
                manga = manga(1L, title = "Favourite & History", tags = setOf(tag("Action"), tag("Drama"))),
            ),
            // Same manga in a second category: the records must merge, not duplicate.
            UsagiFavouriteBackup(
                mangaId = 1L,
                categoryId = 12L,
                createdAt = 1_700_000_200_000L,
                manga = manga(1L, title = "Favourite & History", tags = setOf(tag("Action"))),
            ),
            UsagiFavouriteBackup(
                mangaId = 2L,
                categoryId = 11L,
                createdAt = 1_700_000_300_000L,
                manga = manga(2L, title = "Favourite only"),
            ),
        )
        val history = listOf(
            // Older row must lose against the newer one below.
            UsagiHistoryBackup(
                mangaId = 1L,
                createdAt = 1_600_000_000_000L,
                updatedAt = 1_600_000_000_000L,
                chapterId = 100L,
                page = 3,
                scroll = 0f,
                percent = 0.25f,
                chaptersCount = 10,
                manga = manga(1L, title = "Favourite & History"),
            ),
            UsagiHistoryBackup(
                mangaId = 1L,
                createdAt = 1_600_000_000_000L,
                updatedAt = 1_700_000_400_000L,
                chapterId = 101L,
                page = 7,
                scroll = 0f,
                percent = 0.8f,
                chaptersCount = 12,
                manga = manga(1L, title = "Favourite & History"),
            ),
            UsagiHistoryBackup(
                mangaId = 3L,
                createdAt = 1_600_000_000_000L,
                updatedAt = 1_700_000_500_000L,
                chapterId = 300L,
                page = 0,
                scroll = 0f,
                percent = 0.5f,
                chaptersCount = 4,
                manga = manga(3L, title = "History only", source = "COMICK"),
            ),
        )
        return UsagiRawBackupEntries(
            indexJson = indexJson(),
            categoriesJson = arrayJson(UsagiCategoryBackup.serializer(), categories),
            favouritesJson = arrayJson(UsagiFavouriteBackup.serializer(), favourites),
            historyJson = arrayJson(UsagiHistoryBackup.serializer(), history),
        )
    }

    @Test
    fun `an exported Usagi backup decodes into categories and merged manga records`() {
        val payload = decodeUsagiBackupEntries(sampleEntries())

        assertEquals(listOf("Reading", "Later"), payload.favoriteCategories.map { it.name })
        assertEquals(listOf(11L, 12L), payload.favoriteCategories.map { it.order })
        assertEquals(listOf(11L, 12L), payload.favoriteCategories.map { it.id })

        // mangaId 1 appears twice in favourites and twice in history: one record survives.
        assertEquals(listOf("Favourite & History", "Favourite only", "History only"), payload.records.map { it.title })
        val merged = payload.records.first()
        assertEquals(ExternalBackupApp.USAGI, merged.app)
        assertEquals("MANGA_DEX", merged.sourceName)
        assertEquals(ContentType.MANGA, merged.contentType)
        assertEquals("/manga/1", merged.url)
        assertEquals("https://example.org/manga/1", merged.publicUrl)
        assertEquals("Author 1", merged.authors)
        assertEquals("ONGOING", merged.state)
        assertEquals("https://example.org/cover/1.jpg", merged.coverUrl)
        assertEquals(listOf("Action", "Drama"), merged.tags)
        assertTrue(merged.isFavorite)
        // Earliest favourite wins, like the existing external-import merge.
        assertEquals(1_700_000_100_000L, merged.favoriteTimestamp)
        assertEquals(listOf(11L, 12L), merged.favoriteCategoryOrders)
        // Newest history row decides progress, chapter and chapter count.
        assertEquals("usagi:101", merged.historyChapterUrl)
        assertEquals(1_700_000_400_000L, merged.historyTimestamp)
        assertEquals(7, merged.historyPage)
        assertEquals(0.8f, merged.progressPercent!!, 0.0001f)
        assertEquals(12, merged.chaptersCount)
    }

    @Test
    fun `favourite-only and history-only manga keep their own halves`() {
        val payload = decodeUsagiBackupEntries(sampleEntries())
        val favouriteOnly = payload.records.single { it.title == "Favourite only" }
        assertTrue(favouriteOnly.isFavorite)
        assertNull(favouriteOnly.historyChapterUrl)
        assertNull(favouriteOnly.historyTimestamp)
        assertNull(favouriteOnly.progressPercent)
        assertEquals(0, favouriteOnly.chaptersCount)

        val historyOnly = payload.records.single { it.title == "History only" }
        assertFalse(historyOnly.isFavorite)
        assertEquals("COMICK", historyOnly.sourceName)
        assertEquals("usagi:300", historyOnly.historyChapterUrl)
        assertEquals(1_700_000_500_000L, historyOnly.historyTimestamp)
        assertEquals(0.5f, historyOnly.progressPercent!!, 0.0001f)
        assertEquals(4, historyOnly.chaptersCount)
    }

    @Test
    fun `section map decoding reads the same sections and ignores the rest`() {
        val entries = sampleEntries()
        val sections = mapOf(
            "index" to entries.indexJson!!,
            "categories" to entries.categoriesJson!!,
            "favourites" to entries.favouritesJson!!,
            "history" to entries.historyJson!!,
            // Written by the exporter but not imported.
            "bookmarks" to "[{\"manga\":{}}]",
            "sources" to "[{\"source\":\"MANGA_DEX\"}]",
            "scrobbling" to "[]",
            "statistics" to "[]",
        )

        assertEquals(decodeUsagiBackupEntries(entries), decodeUsagiBackupSections(sections))
        assertEquals("index", usagiBackupEntryName("index"))
        assertEquals("history", usagiBackupEntryName("some/folder/history"))
        assertNull(usagiBackupEntryName("bookmarks"))
        assertNull(usagiBackupEntryName("sources"))
    }

    @Test
    fun `a missing index section fails instead of returning an empty payload`() {
        val exception = assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(indexJson = null))
        }
        assertTrue(exception.message!!.contains("index"), exception.message)

        assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupSections(emptyMap())
        }
    }

    @Test
    fun `an index that is not Usagi's is rejected`() {
        // Kototoro's own backup also has an "index" section, without Usagi's app_id.
        assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(indexJson = "[{\"format\":2,\"createdAt\":1}]"))
        }
        assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(indexJson = indexJson(appId = "org.skepsun.kototoro")))
        }
        assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(indexJson = "[]"))
        }
    }

    @Test
    fun `malformed sections fail with a corrupt-backup error`() {
        val exception = assertThrows(ExternalBackupCorruptException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(favouritesJson = "[{\"manga_id\":1,"))
        }
        assertTrue(exception.message!!.contains("favourites"), exception.message)

        assertThrows(ExternalBackupCorruptException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(indexJson = "not json"))
        }
        // A required field of a category is missing: still a decodable-JSON but invalid payload.
        assertThrows(ExternalBackupCorruptException::class.java) {
            decodeUsagiBackupEntries(sampleEntries().copy(categoriesJson = "[{\"title\":\"no id\"}]"))
        }
    }

    @Test
    fun `an oversized section is rejected before any parsing`() {
        val entries = sampleEntries()
        val index = "[{\"app_id\":\"$USAGI_BACKUP_APP_ID\"}]"
        // The index fits, the favourites section does not: the size check names the offending section.
        val limit = index.toByteArray().size.toLong()
        assertTrue(entries.favouritesJson!!.toByteArray().size > limit)
        val exception = assertThrows(ExternalBackupTooLargeException::class.java) {
            decodeUsagiBackupEntries(
                UsagiRawBackupEntries(indexJson = index, favouritesJson = entries.favouritesJson),
                maxEntryBytes = limit,
            )
        }
        assertTrue(exception.message!!.contains("favourites"), exception.message)

        // The default limit is #747's 64 MiB per-entry ceiling.
        assertEquals(64L * 1024 * 1024, DEFAULT_MAX_USAGI_BACKUP_ENTRY_BYTES)
        decodeUsagiBackupEntries(entries, maxEntryBytes = DEFAULT_MAX_USAGI_BACKUP_ENTRY_BYTES)
    }
}
