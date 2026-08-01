package org.skepsun.kototoro.backups.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.data.model.FavouriteBackup
import org.skepsun.kototoro.backups.data.model.ContentBackup
import org.skepsun.kototoro.backups.data.model.HistoryBackup
import org.skepsun.kototoro.backups.data.model.StatisticBackup
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.stats.data.WorkStatsEntity

class KotatsuBackupPayloadCompatTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `Kotatsu favourite without updated timestamp uses creation timestamp`() {
        val manga = testManga(id = 42L)
        val favourite = FavouriteBackup(
            mangaId = 42L,
            categoryId = 3L,
            createdAt = 1234L,
            manga = ContentBackup(manga),
        )

        assertEquals(1234L, favourite.toEntity().updatedAt)
    }

    @Test
    fun `work state projections remain decodable by Kotatsu backup models`() {
        val manga = testManga(id = 42L)
        val history = HistoryBackup(
            entity = WorkHistoryEntity(
                entityId = 7L,
                anchorMangaId = manga.manga.id,
                createdAt = 10L,
                updatedAt = 20L,
                chapterId = 30L,
                page = 4,
                scroll = 0.25f,
                percent = 0.5f,
                deletedAt = 0L,
                chaptersCount = 12,
            ),
            manga = manga,
        )
        val favourite = FavouriteBackup(
            entity = WorkFavouriteEntity(
                entityId = 7L,
                categoryId = 3L,
                anchorMangaId = manga.manga.id,
                sortKey = 2,
                isPinned = true,
                createdAt = 11L,
                deletedAt = 0L,
                updatedAt = 21L,
            ),
            manga = manga,
        )
        val statistic = StatisticBackup(
            WorkStatsEntity(
                entityId = 7L,
                anchorMangaId = manga.manga.id,
                startedAt = 15L,
                duration = 600L,
                pages = 8,
            ),
        )

        val kotatsuHistory = json.decodeFromString<KotatsuHistoryBackup>(json.encodeToString(history))
        val kotatsuFavourite = json.decodeFromString<KotatsuFavouriteBackup>(json.encodeToString(favourite))
        val kotatsuStatistic = json.decodeFromString<KotatsuStatisticBackup>(json.encodeToString(statistic))

        assertEquals(42L, kotatsuHistory.mangaId)
        assertEquals(30L, kotatsuHistory.chapterId)
        assertEquals("Test manga", kotatsuHistory.manga.title)
        assertEquals(42L, kotatsuFavourite.mangaId)
        assertEquals(3L, kotatsuFavourite.categoryId)
        assertEquals(true, kotatsuFavourite.isPinned)
        assertEquals(42L, kotatsuStatistic.mangaId)
        assertEquals(600L, kotatsuStatistic.duration)
    }

    private fun testManga(id: Long) = MangaWithTags(
        manga = MangaEntity(
            id = id,
            title = "Test manga",
            altTitles = null,
            url = "/manga/test",
            publicUrl = "https://example.org/manga/test",
            rating = 0.8f,
            isNsfw = false,
            contentRating = null,
            coverUrl = "https://example.org/cover.jpg",
            largeCoverUrl = null,
            state = null,
            authors = "Author",
            source = "TEST",
        ),
        tags = emptyList(),
    )

    @Serializable
    private data class KotatsuHistoryBackup(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("chapter_id") val chapterId: Long,
        @SerialName("manga") val manga: KotatsuMangaBackup,
    )

    @Serializable
    private data class KotatsuFavouriteBackup(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("category_id") val categoryId: Long,
        @SerialName("pinned") val isPinned: Boolean,
        @SerialName("manga") val manga: KotatsuMangaBackup,
    )

    @Serializable
    private data class KotatsuStatisticBackup(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("duration") val duration: Long,
    )

    @Serializable
    private data class KotatsuMangaBackup(
        @SerialName("id") val id: Long,
        @SerialName("title") val title: String,
        @SerialName("url") val url: String,
        @SerialName("public_url") val publicUrl: String,
        @SerialName("cover_url") val coverUrl: String,
        @SerialName("source") val source: String,
    )
}
