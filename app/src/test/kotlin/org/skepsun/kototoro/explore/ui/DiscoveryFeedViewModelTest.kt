package org.skepsun.kototoro.explore.ui

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.DiscoveryFeedConfigRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowFetcher
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoader
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryFeedViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private data class FakeSource(
        override val name: String,
        override val locale: String = "en",
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource

    private fun testContent(id: Long, title: String = "Item $id"): Content = Content(
        id = id,
        title = title,
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

    private fun createSettings(
        nsfwDisabled: AtomicBoolean = AtomicBoolean(false),
        changes: MutableSharedFlow<String?> = MutableSharedFlow(extraBufferCapacity = 16),
    ): AppSettings {
        val settings = mockk<AppSettings>(relaxed = true)
        every { settings.isNsfwContentDisabled } answers { nsfwDisabled.get() }
        every { settings.observeChanges() } returns changes
        return settings
    }

    private fun createHarness(
        clock: AtomicLong = AtomicLong(1_000L),
        nsfwDisabled: AtomicBoolean = AtomicBoolean(false),
        settingsChanges: MutableSharedFlow<String?> = MutableSharedFlow(extraBufferCapacity = 16),
        fetcher: DiscoveryFeedRowFetcher,
    ): DiscoveryFeedViewModel {
        val idCounter = AtomicInteger(0)
        val configRepository = DiscoveryFeedConfigRepository(
            prefs = MemorySyncPreferences(),
            idGenerator = { "row-${idCounter.incrementAndGet()}" },
        )
        val loader = DiscoveryFeedRowLoader(
            fetcher = fetcher,
            isNsfwContentDisabled = { nsfwDisabled.get() },
            clock = { clock.get() },
        )
        return DiscoveryFeedViewModel(
            configRepository = configRepository,
            rowLoader = loader,
            settings = createSettings(nsfwDisabled, settingsChanges),
        )
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and CRUD actions update allRows and visibleRows`() = runTest(testDispatcher) {
        val vm = createHarness { emptyList() }
        advanceUntilIdle()

        assertEquals(emptyList<DiscoveryFeedRow>(), vm.uiState.value.allRows)
        assertEquals(emptyList<DiscoveryFeedRow>(), vm.uiState.value.visibleRows)
        assertFalse(vm.uiState.value.isRefreshing)

        val r1 = vm.addRow(sourceName = "S1", displayName = "First", query = "action")
        val r2 = vm.addRow(sourceName = "S2", displayName = "Second", savedFilterId = 7)
        advanceUntilIdle()

        assertEquals(listOf(r1.id, r2.id), vm.uiState.value.allRows.map { it.id })
        assertEquals(listOf(r1.id, r2.id), vm.uiState.value.visibleRows.map { it.id })
        // Lazy: rows remain Idle until onRowVisible is called.
        assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(r1.id))
        assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(r2.id))

        vm.toggleRowHidden(r1.id)
        advanceUntilIdle()
        assertEquals(listOf(r1.id, r2.id), vm.uiState.value.allRows.map { it.id })
        assertEquals(listOf(r2.id), vm.uiState.value.visibleRows.map { it.id })

        vm.reorderRows(listOf(r2.id, r1.id))
        advanceUntilIdle()
        assertEquals(listOf(r2.id, r1.id), vm.uiState.value.allRows.map { it.id })

        vm.deleteRow(r2.id)
        advanceUntilIdle()
        assertEquals(listOf(r1.id), vm.uiState.value.allRows.map { it.id })
        assertEquals(emptyList<DiscoveryFeedRow>(), vm.uiState.value.visibleRows)
    }

    @Test
    fun `onRowVisible uses cache within TTL refetches after expiry and retryRow bypasses cache`() = runTest(testDispatcher) {
        val clock = AtomicLong(10_000L)
        val fetchCount = AtomicInteger(0)
        val vm = createHarness(clock = clock) { row ->
            val n = fetchCount.incrementAndGet()
            listOf(testContent(id = n.toLong(), title = "${row.id}-$n"))
        }
        val row = vm.addRow(sourceName = "S1", displayName = "Row 1")
        advanceUntilIdle()

        // First visibility triggers load.
        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(1, fetchCount.get())
        val firstLoaded = assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(row.id))
        assertEquals(10_000L, firstLoaded.fetchedAtMs)

        // Second visibility within 30m hits cache without fetching.
        clock.addAndGet(DiscoveryFeedRowLoader.DEFAULT_CACHE_TTL_MS - 5_000L)
        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(1, fetchCount.get())

        // Retry bypasses cache even within TTL.
        vm.retryRow(row.id)
        advanceUntilIdle()
        assertEquals(2, fetchCount.get())
        val retried = assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(row.id))
        assertEquals(clock.get(), retried.fetchedAtMs)

        // After 30m TTL expires, onRowVisible triggers a fresh load.
        clock.addAndGet(DiscoveryFeedRowLoader.DEFAULT_CACHE_TTL_MS)
        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(3, fetchCount.get())
    }

    @Test
    fun `refreshAll isolates single-row failure and respects concurrency cap of 3`() = runTest(testDispatcher) {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val startedCount = AtomicInteger(0)
        val fetchCounts = ConcurrentHashMap<String, AtomicInteger>()

        val vm = createHarness { row ->
            fetchCounts.computeIfAbsent(row.id) { AtomicInteger(0) }.incrementAndGet()
            val cur = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(cur, ::maxOf)
            startedCount.incrementAndGet()
            try {
                release.await()
                if (row.sourceName == "BROKEN") {
                    throw IOException("Network failure")
                }
                listOf(testContent(id = row.id.hashCode().toLong()))
            } finally {
                active.decrementAndGet()
            }
        }

        val rows = listOf(
            vm.addRow(sourceName = "OK1", displayName = "OK 1"),
            vm.addRow(sourceName = "BROKEN", displayName = "Broken"),
            vm.addRow(sourceName = "OK2", displayName = "OK 2"),
            vm.addRow(sourceName = "OK3", displayName = "OK 3"),
            vm.addRow(sourceName = "OK4", displayName = "OK 4"),
            vm.addRow(sourceName = "HIDDEN", displayName = "Hidden"),
        )
        vm.toggleRowHidden(rows.last().id)
        advanceUntilIdle()

        vm.refreshAll()
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.isRefreshing)
        assertEquals(3, active.get())
        assertEquals(3, maxConcurrent.get())

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(3, maxConcurrent.get())
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(rows[0].id))
        val err = assertInstanceOf(DiscoveryFeedRowState.Error::class.java, vm.uiState.value.stateOf(rows[1].id))
        assertEquals("Network failure", err.message)
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(rows[2].id))
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(rows[3].id))
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(rows[4].id))
        // Hidden row was never fetched.
        assertEquals(null, fetchCounts[rows[5].id])
        assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(rows[5].id))
    }

    @Test
    fun `cancelInFlightLoads cancels active and queued row fetches and resets Loading to Idle`() = runTest(testDispatcher) {
        val started = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val neverFinishes = CompletableDeferred<Unit>()

        val vm = createHarness { row ->
            if (row.sourceName == "FAST") {
                return@createHarness listOf(testContent(id = 1L))
            }
            started.incrementAndGet()
            try {
                neverFinishes.await()
                emptyList()
            } catch (e: CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
        }

        val fastRow = vm.addRow(sourceName = "FAST", displayName = "Fast")
        val slowRows = (1..5).map { idx ->
            vm.addRow(sourceName = "SLOW_$idx", displayName = "Slow $idx")
        }
        advanceUntilIdle()

        // Pre-load fastRow so we can verify cancelInFlightLoads keeps already-Loaded rows.
        vm.onRowVisible(fastRow.id)
        advanceUntilIdle()
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(fastRow.id))

        // Start 5 slow rows: 3 active under Semaphore(3), 2 queued.
        slowRows.forEach { vm.onRowVisible(it.id) }
        testScheduler.runCurrent()

        assertEquals(3, started.get())
        slowRows.forEach { row ->
            assertEquals(DiscoveryFeedRowState.Loading, vm.uiState.value.stateOf(row.id))
        }

        // Cancel in-flight loads (e.g. leaving Discovery tab).
        vm.cancelInFlightLoads()
        advanceUntilIdle()

        assertEquals(3, cancelled.get(), "All 3 active fetches should be cancelled")
        assertEquals(3, started.get(), "Queued fetches must never start after cancellation")
        assertFalse(vm.uiState.value.isRefreshing)
        // Slow loading rows reset back to Idle; fastRow stays Loaded.
        slowRows.forEach { row ->
            assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(row.id))
        }
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(fastRow.id))
    }

    @Test
    fun `updateRow query change and NSFW setting change invalidate cached state`() = runTest(testDispatcher) {
        val nsfwDisabled = AtomicBoolean(false)
        val settingsChanges = MutableSharedFlow<String?>(extraBufferCapacity = 16)
        val fetchCount = AtomicInteger(0)

        val vm = createHarness(
            nsfwDisabled = nsfwDisabled,
            settingsChanges = settingsChanges,
        ) { row ->
            val n = fetchCount.incrementAndGet()
            listOf(testContent(id = n.toLong(), title = "${row.query}-$n"))
        }

        val row = vm.addRow(sourceName = "S1", displayName = "Row 1", query = "alpha")
        advanceUntilIdle()

        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(1, fetchCount.get())
        assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(row.id))

        // Updating query invalidates cache & resets row state to Idle.
        vm.updateRow(rowId = row.id, displayName = "Row 1", query = "beta")
        advanceUntilIdle()
        assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(row.id))

        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(2, fetchCount.get())
        val loadedBeta = assertInstanceOf(DiscoveryFeedRowState.Loaded::class.java, vm.uiState.value.stateOf(row.id))
        assertEquals("beta-2", loadedBeta.items.single().title)

        // Toggling NSFW setting clears cached state so row reloads on next visibility.
        nsfwDisabled.set(true)
        settingsChanges.emit(AppSettings.KEY_DISABLE_NSFW)
        advanceUntilIdle()
        assertEquals(DiscoveryFeedRowState.Idle, vm.uiState.value.stateOf(row.id))

        vm.onRowVisible(row.id)
        advanceUntilIdle()
        assertEquals(3, fetchCount.get())
    }
}
