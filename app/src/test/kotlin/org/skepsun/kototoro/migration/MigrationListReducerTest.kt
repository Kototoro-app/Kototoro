package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.migration.ui.list.MigrationFilter
import org.skepsun.kototoro.migration.ui.list.MigrationItemState
import org.skepsun.kototoro.migration.ui.list.MigrationItemStatus
import org.skepsun.kototoro.migration.ui.list.MigrationListState
import org.skepsun.kototoro.parsers.model.Content

class MigrationListReducerTest {
    private fun content(id: Long) = Content(
        id = id, title = "T$id", altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = TestContentSource,
    )

    private fun item(id: Long, status: MigrationItemStatus, old: Int = 10, new: Int? = null) = MigrationItemState(
        origin = content(id), originChapters = old, status = status,
        target = if (new != null) content(id + 100) else null, targetChapters = new,
    )

    private val state = MigrationListState(
        items = listOf(
            item(1, MigrationItemStatus.MATCHED, old = 10, new = 12),
            item(2, MigrationItemStatus.MATCHED, old = 10, new = 8),
            item(3, MigrationItemStatus.NOT_FOUND),
            item(4, MigrationItemStatus.SEARCHING),
        ),
    )

    @Test
    fun `counts per filter`() {
        assertEquals(4, state.count(MigrationFilter.ALL))
        assertEquals(2, state.count(MigrationFilter.MATCHED))
        assertEquals(1, state.count(MigrationFilter.NOT_FOUND))
        assertEquals(1, state.count(MigrationFilter.FEWER_CHAPTERS))
    }

    @Test
    fun `visible items follow the selected filter`() {
        assertEquals(listOf(2L), state.copy(filter = MigrationFilter.FEWER_CHAPTERS).visibleItems.map { it.origin.id })
    }

    @Test
    fun `ready count is matched entries and progress counts settled entries`() {
        assertEquals(2, state.readyCount)
        assertEquals(3, state.settledCount)
    }

    @Test
    fun `chapter delta is new minus old`() {
        assertEquals(2, state.items[0].chapterDelta)
        assertEquals(-2, state.items[1].chapterDelta)
        assertEquals(null, state.items[2].chapterDelta)
    }

    @Test
    fun `update item replaces by origin id`() {
        val updated = state.updateItem(3) { it.copy(status = MigrationItemStatus.SEARCHING) }
        assertEquals(MigrationItemStatus.SEARCHING, updated.items[2].status)
    }
}
