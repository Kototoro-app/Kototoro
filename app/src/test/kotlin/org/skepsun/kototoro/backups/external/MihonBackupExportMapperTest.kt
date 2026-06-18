package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryMembership
import org.skepsun.kototoro.history.data.HistoryEntity

class MihonBackupExportMapperTest {

    @Test
    fun `source id parser only accepts mihon sources`() {
        assertEquals(123456789L, MihonBackupExportMapper.sourceIdOrNull("MIHON_123456789"))
        assertNull(MihonBackupExportMapper.sourceIdOrNull("COPYMANGA"))
        assertNull(MihonBackupExportMapper.sourceIdOrNull("MIHON_invalid"))
    }

    @Test
    fun `chapter export marks chapters before current as read`() {
        val chapters = buildChapters(4)
        val history = HistoryEntity(
            mangaId = 1L,
            createdAt = 100L,
            updatedAt = 200L,
            chapterId = 3L,
            page = 0,
            scroll = 0f,
            percent = 0.5f,
            deletedAt = 0L,
            chaptersCount = 4,
            parentChapterId = null,
        )

        val exportedChapters = MihonBackupExportMapper.buildChapters(chapters, history)
        val exportedHistory = MihonBackupExportMapper.buildHistory(chapters, history)

        assertEquals(listOf(true, true, false, false), exportedChapters.map(MihonBackupChapter::read))
        assertEquals("chapter-3", exportedHistory?.url)
        assertEquals(200L, exportedHistory?.lastRead)
    }

    @Test
    fun `chapter export falls back to percent when current chapter is missing`() {
        val chapters = buildChapters(4)
        val history = HistoryEntity(
            mangaId = 1L,
            createdAt = 100L,
            updatedAt = 200L,
            chapterId = 99L,
            page = 0,
            scroll = 0f,
            percent = 0.5f,
            deletedAt = 0L,
            chaptersCount = 4,
            parentChapterId = null,
        )

        val exportedChapters = MihonBackupExportMapper.buildChapters(chapters, history)

        assertEquals(listOf(true, true, false, false), exportedChapters.map(MihonBackupChapter::read))
        assertNull(MihonBackupExportMapper.buildHistory(chapters, history))
    }

    @Test
    fun `manga categories export uses category order instead of local id`() {
        val memberships = listOf(
            FavouriteCategoryMembership(mangaId = 1L, categoryId = 42L),
            FavouriteCategoryMembership(mangaId = 1L, categoryId = 7L),
        )

        val exported = MihonBackupExportMapper.mapCategoryOrders(
            categoryMemberships = memberships,
            categoryOrderById = mapOf(
                42L to 1L,
                7L to 3L,
            ),
        )

        assertEquals(listOf(1L, 3L), exported)
    }

    private fun buildChapters(count: Int): List<ChapterEntity> {
        return (1..count).map { index ->
            ChapterEntity(
                chapterId = index.toLong(),
                mangaId = 1L,
                title = "Chapter $index",
                number = index.toFloat(),
                volume = 0,
                url = "chapter-$index",
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = "MIHON_1",
                index = index - 1,
            )
        }
    }
}
