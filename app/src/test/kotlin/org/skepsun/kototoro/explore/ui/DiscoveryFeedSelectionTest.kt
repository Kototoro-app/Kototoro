package org.skepsun.kototoro.explore.ui

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.DiscoveryFeedConfigRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.domain.DiscoveryFeedLibraryMembership
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoader
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryFeedSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    private data class FakeSource(
        override val name: String,
        override val locale: String = "en",
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource

    /** Library ids are a mutable set; [changes] stands in for the favourites table tracker. */
    private class FakeLibrary(initial: Set<Long> = emptySet()) : DiscoveryFeedLibraryMembership {
        val ids = initial.toMutableSet()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

        override suspend fun libraryIdsOf(contentIds: Collection<Long>): Set<Long> =
            contentIds.filterTo(HashSet()) { it in ids }

        override fun observeChanges(): Flow<Unit> = changes
    }

    private fun content(id: Long): Content = Content(
        id = id,
        title = "Item $id",
        altTitles = emptySet(),
        url = "/item/$id",
        publicUrl = "https://example.org/item/$id",
        rating = -1f,
        contentRating = ContentRating.SAFE,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = FakeSource("SRC"),
    )

    private val prefs = MemorySyncPreferences()

    /** Row "A" holds 1..3, row "B" holds 3..5: work 3 shows in both rows. */
    private val itemsByRowName = mapOf(
        "A" to listOf(1L, 2L, 3L),
        "B" to listOf(3L, 4L, 5L),
    )

    private fun createViewModel(library: FakeLibrary = FakeLibrary()): DiscoveryFeedViewModel {
        val idCounter = AtomicInteger(0)
        val configRepository = DiscoveryFeedConfigRepository(
            prefs = prefs,
            idGenerator = { "row-${idCounter.incrementAndGet()}" },
        )
        val loader = DiscoveryFeedRowLoader(
            fetcher = { row -> itemsByRowName.getValue(row.displayName).map(::content) },
            isNsfwContentDisabled = { false },
            clock = { 1_000L },
        )
        val settings = mockk<AppSettings>(relaxed = true)
        every { settings.isNsfwContentDisabled } returns false
        every { settings.observeChanges() } returns MutableSharedFlow()
        return DiscoveryFeedViewModel(
            configRepository = configRepository,
            rowLoader = loader,
            settings = settings,
            libraryMembership = library,
        )
    }

    private fun DiscoveryFeedViewModel.loadRows(): Pair<DiscoveryFeedRow, DiscoveryFeedRow> {
        val a = addRow(sourceName = "S1", displayName = "A")
        val b = addRow(sourceName = "S2", displayName = "B")
        onRowVisible(a.id)
        onRowVisible(b.id)
        return a to b
    }

    private fun DiscoveryFeedUiState.cardIds(rowId: String): List<Long> = cardModelsOf(rowId).map { it.manga.id }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `long press enters selection and removing the last pick or clearing exits it`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val (a, _) = vm.loadRows()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSelectionMode)

        vm.toggleSelection(a.id, 1L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSelectionMode)
        assertEquals(setOf(1L), vm.uiState.value.selectedIds)
        assertEquals(a.id, vm.uiState.value.selectionRowId)

        vm.toggleSelection(a.id, 1L)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSelectionMode)
        assertNull(vm.uiState.value.selectionRowId)

        vm.toggleSelection(a.id, 2L)
        vm.clearSelection()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSelectionMode)
    }

    @Test
    fun `selection spans rows and select all fills only the row touched last`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val (a, b) = vm.loadRows()
        advanceUntilIdle()

        vm.toggleSelection(a.id, 1L)
        vm.toggleSelection(b.id, 4L)
        advanceUntilIdle()
        assertEquals(setOf(1L, 4L), vm.uiState.value.selectedIds)
        assertEquals(b.id, vm.uiState.value.selectionRowId)

        vm.selectAllInCurrentRow()
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L, 4L, 5L), vm.uiState.value.selectedIds)

        // Work 3 shows in both rows but is one pick, listed once in row order.
        assertEquals(listOf(1L, 3L, 4L, 5L), vm.selectedContents().map { it.id })
    }

    @Test
    fun `picks of a row that gets hidden drop out of the selection`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val (a, b) = vm.loadRows()
        advanceUntilIdle()

        vm.toggleSelection(a.id, 1L)
        vm.toggleSelection(b.id, 5L)
        vm.toggleRowHidden(b.id)
        advanceUntilIdle()

        assertEquals(setOf(1L), vm.uiState.value.selectedIds)
        assertNull(vm.uiState.value.selectionRowId)
    }

    @Test
    fun `bulk add skips works already in the library and counts what was added`() = runTest(testDispatcher) {
        val library = FakeLibrary(initial = setOf(2L))
        val vm = createViewModel(library)
        val (a, _) = vm.loadRows()
        advanceUntilIdle()
        vm.toggleSelection(a.id, 1L)
        vm.selectAllInCurrentRow()
        advanceUntilIdle()

        val plan = vm.planBulkAddToLibrary()
        assertEquals(listOf(1L, 3L), plan.toAdd.map { it.id })
        assertEquals(1, plan.alreadyInLibrary)

        // The category dialog adds what it was given.
        library.ids += plan.toAdd.map { it.id }
        val result = vm.completeBulkAddToLibrary(plan)
        advanceUntilIdle()

        assertEquals(DiscoveryFeedBulkAddResult(added = 2, skipped = 1), result)
        assertFalse(vm.uiState.value.isSelectionMode)
    }

    @Test
    fun `closing the dialog without a category keeps the selection`() = runTest(testDispatcher) {
        val vm = createViewModel(FakeLibrary())
        val (a, _) = vm.loadRows()
        advanceUntilIdle()
        vm.toggleSelection(a.id, 1L)
        advanceUntilIdle()

        val plan = vm.planBulkAddToLibrary()
        val result = vm.completeBulkAddToLibrary(plan)
        advanceUntilIdle()

        assertEquals(DiscoveryFeedBulkAddResult(added = 0, skipped = 0), result)
        assertEquals(setOf(1L), vm.uiState.value.selectedIds)
    }

    @Test
    fun `hide in library filters every row and is persisted`() = runTest(testDispatcher) {
        val library = FakeLibrary(initial = setOf(3L, 4L))
        val vm = createViewModel(library)
        val (a, b) = vm.loadRows()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hideInLibrary)
        assertEquals(listOf(3L, 4L, 5L), vm.uiState.value.cardIds(b.id))

        vm.setHideInLibrary(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hideInLibrary)
        assertEquals(listOf(1L, 2L), vm.uiState.value.cardIds(a.id))
        assertEquals(listOf(5L), vm.uiState.value.cardIds(b.id))

        // A favourite added elsewhere disappears once the library reports the change.
        library.ids += 5L
        library.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), vm.uiState.value.cardIds(b.id))

        // A fresh view model (app restart) reads the stored setting.
        val restored = createViewModel(library)
        advanceUntilIdle()
        assertTrue(restored.uiState.value.hideInLibrary)

        vm.setHideInLibrary(false)
        advanceUntilIdle()
        assertEquals(listOf(3L, 4L, 5L), vm.uiState.value.cardIds(b.id))
    }
}
