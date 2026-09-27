package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
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
            favourite(categoryId = 42L),
            favourite(categoryId = 7L),
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

    @Test
    fun `category orders stay unique when sort keys collide`() {
        // Mihon identifies a manga's categories by order, so equal sort keys must not merge.
        val orders = MihonBackupExportMapper.assignCategoryOrders(
            listOf(category(id = 2, sortKey = 1), category(id = 10, sortKey = 9), category(id = 1, sortKey = 1)),
        )

        assertEquals(mapOf(1L to 0L, 2L to 1L, 10L to 2L), orders)
    }

    private fun category(id: Int, sortKey: Int) = FavouriteCategoryEntity(
        categoryId = id,
        createdAt = 0L,
        sortKey = sortKey,
        title = "c$id",
        order = "NEWEST",
        track = true,
        isVisibleInLibrary = true,
        deletedAt = 0L,
    )

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

    private fun favourite(categoryId: Long) = FavouriteEntity(
        mangaId = 1L,
        categoryId = categoryId,
        sortKey = 0,
        isPinned = false,
        createdAt = 0L,
        deletedAt = 0L,
        updatedAt = 0L,
    )
}
