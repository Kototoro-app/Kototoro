package org.skepsun.kototoro.list.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.ui.model.ListModel

class RetainedPagingSnapshotTest {

    @Test
    fun `capture keeps a bounded window and normalizes the viewport`() {
        val items = List(1_000) { TestListModel(it.toLong()) }

        val snapshot = createRetainedPagingSnapshot(
            loadedItems = items,
            clickedItem = items[500],
            listMode = ListMode.LIST,
            layoutFirstVisibleIndex = 503,
            firstVisibleItemScrollOffset = 17,
            pagingAnchorIndex = 500,
        )

        requireNotNull(snapshot)
        assertEquals(RETAINED_PAGING_SNAPSHOT_MAX_ITEMS, snapshot.items.size)
        assertSame(items[500], snapshot.anchorItem)
        assertEquals(64, snapshot.anchorItemIndex)
        assertEquals(67, snapshot.firstVisibleItemIndex)
        assertEquals(3, snapshot.liveLayoutOffset)
        assertEquals(17, snapshot.firstVisibleItemScrollOffset)
    }

    @Test
    fun `capture falls back to clicked model identity when viewport is not anchorable`() {
        val clicked = TestListModel(9)
        val items = listOf(NonAnchorListModel, clicked)

        val snapshot = createRetainedPagingSnapshot(
            loadedItems = items,
            clickedItem = clicked,
            listMode = ListMode.LIST,
            layoutFirstVisibleIndex = 0,
            firstVisibleItemScrollOffset = 0,
            pagingAnchorIndex = 0,
        )

        requireNotNull(snapshot)
        assertSame(clicked, snapshot.anchorItem)
        assertEquals(1, snapshot.anchorItemIndex)
    }

    @Test
    fun `store only clears the requested generation`() {
        val store = RetainedPagingSnapshotStore()
        val first = snapshot(TestListModel(1))
        val second = snapshot(TestListModel(2))

        store.retainPagingSnapshot(first)
        val firstGeneration = requireNotNull(store.peekRetainedPagingSnapshot()).generation
        store.retainPagingSnapshot(second)
        val retained = requireNotNull(store.peekRetainedPagingSnapshot())

        store.clearRetainedPagingSnapshot(firstGeneration)
        assertEquals(retained, store.peekRetainedPagingSnapshot())
        store.clearRetainedPagingSnapshot(retained.generation)
        assertNull(store.peekRetainedPagingSnapshot())
    }

    private fun snapshot(anchor: ListModel) = RetainedPagingSnapshot(
        generation = 0,
        items = listOf(anchor),
        anchorItem = anchor,
        anchorItemIndex = 0,
        listMode = ListMode.LIST,
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        liveLayoutOffset = 0,
    )

    private data class TestListModel(val id: Long) : ListModel {
        override fun areItemsTheSame(other: ListModel): Boolean = other is TestListModel && other.id == id
    }

    private data object NonAnchorListModel : ListModel {
        override fun areItemsTheSame(other: ListModel): Boolean = false
    }
}
