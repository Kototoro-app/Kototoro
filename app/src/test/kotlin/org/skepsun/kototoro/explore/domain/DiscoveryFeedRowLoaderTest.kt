package org.skepsun.kototoro.explore.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentSourceResolutionPipeline
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DiscoveryFeedRowLoaderTest {

    private data class FakeSource(
        override val name: String,
        override val locale: String = "en",
        override val contentType: ContentType = ContentType.MANGA,
    ) : ContentSource

    private fun testContent(
        id: Long,
        title: String = "Item $id",
        source: ContentSource = FakeSource("SRC_A"),
        rating: ContentRating? = ContentRating.SAFE,
        tags: Set<ContentTag> = emptySet(),
    ): Content = Content(
        id = id,
        title = title,
        altTitles = emptySet(),
        url = "/item/$id",
        publicUrl = "https://example.org/item/$id",
        rating = -1f,
        contentRating = rating,
        coverUrl = null,
        tags = tags,
        state = null,
        authors = emptySet(),
        source = source,
    )

    private fun testRow(
        id: String,
        sourceName: String = "SRC_$id",
        savedFilterId: Int? = null,
        query: String? = null,
    ): DiscoveryFeedRow = DiscoveryFeedRow(
        id = id,
        sourceName = sourceName,
        savedFilterId = savedFilterId,
        query = query,
        displayName = "Row $id",
    )

    @Test
    fun `global concurrency cap never exceeds 3`() = runTest {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val startedGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val releaseGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val rows = (1..8).map { i ->
            val id = "r$i"
            startedGates[id] = CompletableDeferred()
            releaseGates[id] = CompletableDeferred()
            testRow(id)
        }

        val loader = DiscoveryFeedRowLoader(
            fetcher = { row ->
                val nowActive = active.incrementAndGet()
                maxConcurrent.accumulateAndGet(nowActive, ::maxOf)
                startedGates.getValue(row.id).complete(Unit)
                try {
                    releaseGates.getValue(row.id).await()
                    listOf(testContent(id = row.id.removePrefix("r").toLong()))
                } finally {
                    active.decrementAndGet()
                }
            },
            isNsfwContentDisabled = { false },
            clock = { 1_000L },
        )

        val deferreds = rows.map { row ->
            async { loader.loadRow(row) }
        }

        // Let the first batch acquire permits.
        repeat(20) { yield() }
        assertEquals(3, active.get())
        assertEquals(3, maxConcurrent.get())
        val startedInitially = startedGates.values.count { it.isCompleted }
        assertEquals(3, startedInitially)

        // Release rows one wave at a time and verify concurrency never exceeds 3.
        releaseGates.values.forEach { gate ->
            gate.complete(Unit)
            repeat(5) { yield() }
            assertTrue(maxConcurrent.get() <= 3, "Observed max concurrency ${maxConcurrent.get()} > 3")
        }

        val results = deferreds.awaitAll()
        assertEquals(8, results.size)
        assertTrue(results.all { it is DiscoveryFeedRowLoadResult.Loaded })
        assertEquals(3, maxConcurrent.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `30-minute TTL caches hits before expiry refetches after expiry and force refresh bypasses cache`() = runTest {
        val now = AtomicLong(10_000L)
        val fetchCount = AtomicInteger(0)
        val row = testRow("r1")

        val loader = DiscoveryFeedRowLoader(
            fetcher = {
                val n = fetchCount.incrementAndGet()
                listOf(testContent(id = n.toLong(), title = "Batch $n"))
            },
            isNsfwContentDisabled = { false },
            clock = { now.get() },
        )

        // First load populates cache.
        val first = loader.loadRow(row) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(1, fetchCount.get())
        assertEquals(10_000L, first.fetchedAtMs)
        assertEquals("Batch 1", first.items.single().title)

        // Advance 29m59s -> cache hit without refetch.
        now.addAndGet(DiscoveryFeedRowLoader.DEFAULT_CACHE_TTL_MS - 1_000L)
        assertNotNull(loader.getCached(row))
        val cachedHit = loader.loadRow(row) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(1, fetchCount.get())
        assertEquals(10_000L, cachedHit.fetchedAtMs)
        assertEquals("Batch 1", cachedHit.items.single().title)

        // Force refresh before TTL expiry -> bypasses cache and refetches.
        val forced = loader.loadRow(row, forceRefresh = true) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(2, fetchCount.get())
        assertEquals(now.get(), forced.fetchedAtMs)
        assertEquals("Batch 2", forced.items.single().title)

        // Advance exactly 30m from forced fetch -> cache expired, refetches.
        now.addAndGet(DiscoveryFeedRowLoader.DEFAULT_CACHE_TTL_MS)
        assertNull(loader.getCached(row))
        val afterExpiry = loader.loadRow(row) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(3, fetchCount.get())
        assertEquals(now.get(), afterExpiry.fetchedAtMs)
        assertEquals("Batch 3", afterExpiry.items.single().title)
    }

    @Test
    fun `changing row query or nsfw setting invalidates cache fingerprint`() = runTest {
        var nsfwDisabled = false
        val fetchCount = AtomicInteger(0)
        val nsfwItem = testContent(id = 1L, rating = ContentRating.ADULT)
        val sfwItem = testContent(id = 2L, rating = ContentRating.SAFE)

        val loader = DiscoveryFeedRowLoader(
            fetcher = {
                fetchCount.incrementAndGet()
                listOf(nsfwItem, sfwItem)
            },
            isNsfwContentDisabled = { nsfwDisabled },
            clock = { 1_000L },
        )

        val rowQueryA = testRow("r1", query = "alpha")
        val first = loader.loadRow(rowQueryA) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(listOf(1L, 2L), first.items.map { it.id })
        assertEquals(1, fetchCount.get())

        // Changing query misses cache.
        val rowQueryB = rowQueryA.copy(query = "beta")
        loader.loadRow(rowQueryB)
        assertEquals(2, fetchCount.get())

        // Enabling NSFW restriction misses cache and filters NSFW items out.
        nsfwDisabled = true
        val sfwOnly = loader.loadRow(rowQueryB) as DiscoveryFeedRowLoadResult.Loaded
        assertEquals(3, fetchCount.get())
        assertEquals(listOf(2L), sfwOnly.items.map { it.id })
    }

    @Test
    fun `single-row failure is isolated and sibling rows still succeed`() = runTest {
        val boom = IOException("Source offline")
        val loader = DiscoveryFeedRowLoader(
            fetcher = { row ->
                if (row.id == "bad") throw boom
                listOf(testContent(id = row.id.hashCode().toLong()))
            },
            isNsfwContentDisabled = { false },
            clock = { 5_000L },
        )

        val rows = listOf(testRow("ok1"), testRow("bad"), testRow("ok2"), testRow("ok3"))
        val results = rows.map { row ->
            async { row.id to loader.loadRow(row) }
        }.awaitAll().toMap()

        assertInstanceOf(DiscoveryFeedRowLoadResult.Loaded::class.java, results["ok1"])
        assertInstanceOf(DiscoveryFeedRowLoadResult.Loaded::class.java, results["ok2"])
        assertInstanceOf(DiscoveryFeedRowLoadResult.Loaded::class.java, results["ok3"])
        val errorResult = assertInstanceOf(DiscoveryFeedRowLoadResult.Error::class.java, results["bad"])
        assertEquals(boom, errorResult.throwable)
    }

    @Test
    fun `cancellation propagates as CancellationException and releases semaphore permit`() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        var caughtCancellation = false

        val loader = DiscoveryFeedRowLoader(
            fetcher = { row ->
                if (row.id == "stuck") {
                    started.complete(Unit)
                    neverFinishes.await()
                }
                listOf(testContent(id = 99L))
            },
            isNsfwContentDisabled = { false },
            clock = { 1_000L },
            maxConcurrency = 1,
        )

        val job = launch {
            try {
                loader.loadRow(testRow("stuck"))
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(caughtCancellation, "CancellationException must not be swallowed into Error")

        // If the semaphore permit was released, another load with maxConcurrency=1 succeeds immediately.
        val next = loader.loadRow(testRow("next"))
        assertInstanceOf(DiscoveryFeedRowLoadResult.Loaded::class.java, next)
    }

    @Test
    fun `DefaultDiscoveryFeedRowFetcher skips NSFW source and filters NSFW items when disabled`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nsfwSource = FakeSource("NSFW_SRC", contentType = ContentType.HENTAI_MANGA)
        val sfwSource = FakeSource("SFW_SRC", contentType = ContentType.MANGA)

        val pipeline = mockk<ContentSourceResolutionPipeline>()
        every { pipeline.resolveByName("NSFW_SRC") } returns nsfwSource
        every { pipeline.resolveByName("SFW_SRC") } returns sfwSource
        every { pipeline.resolveByName("MISSING_SRC") } returns UnknownContentSource

        val sfwRepo = mockk<ContentRepository>()
        every { sfwRepo.source } returns sfwSource
        every { sfwRepo.sortOrders } returns setOf(SortOrder.POPULARITY, SortOrder.RELEVANCE)
        every { sfwRepo.defaultSortOrder } returns SortOrder.POPULARITY
        every { sfwRepo.filterCapabilities } returns ContentListFilterCapabilities()
        coEvery { sfwRepo.getList(0, SortOrder.RELEVANCE, ContentListFilter(query = "magic")) } returns listOf(
            testContent(id = 1L, source = sfwSource, rating = ContentRating.SAFE),
            testContent(id = 2L, source = sfwSource, rating = ContentRating.ADULT),
            testContent(id = 1L, source = sfwSource, rating = ContentRating.SAFE), // duplicate
        )

        val factory = mockk<ContentRepository.Factory>()
        every { factory.create(sfwSource) } returns sfwRepo

        val savedFiltersRepo = mockk<SavedFiltersRepository>()
        val settings = mockk<AppSettings>()
        every { settings.isNsfwContentDisabled } returns true
        every { settings.globalTagBlacklist } returns emptySet()

        val fetcher = DefaultDiscoveryFeedRowFetcher(
            sourceResolutionPipeline = pipeline,
            contentRepositoryFactory = factory,
            savedFiltersRepository = savedFiltersRepo,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        // NSFW source returns emptyList without hitting repository.
        val nsfwResult = fetcher.fetch(testRow("r1", sourceName = "NSFW_SRC"))
        assertEquals(emptyList<Content>(), nsfwResult)
        coVerify(exactly = 0) { factory.create(nsfwSource) }

        // SFW source filters NSFW item & deduplicates by id.
        val sfwResult = fetcher.fetch(testRow("r2", sourceName = "SFW_SRC", query = "magic"))
        assertEquals(listOf(1L), sfwResult.map { it.id })

        // Unresolved source throws DiscoveryFeedSourceNotFoundException.
        var unresolvedError: Throwable? = null
        try {
            fetcher.fetch(testRow("r3", sourceName = "MISSING_SRC"))
        } catch (e: Throwable) {
            unresolvedError = e
        }
        assertInstanceOf(DiscoveryFeedSourceNotFoundException::class.java, unresolvedError)
    }

    @Test
    fun `DefaultDiscoveryFeedRowFetcher resolves saved filter and throws if deleted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource("MANGADEX")
        val tag = ContentTag("Action", "action", source)
        val filter = ContentListFilter(tags = setOf(tag))

        val pipeline = mockk<ContentSourceResolutionPipeline>()
        every { pipeline.resolveByName("MANGADEX") } returns source

        val repo = mockk<ContentRepository>()
        every { repo.source } returns source
        every { repo.sortOrders } returns setOf(SortOrder.UPDATED)
        every { repo.defaultSortOrder } returns SortOrder.UPDATED
        every { repo.filterCapabilities } returns ContentListFilterCapabilities()
        coEvery { repo.getList(0, SortOrder.UPDATED, filter) } returns listOf(
            testContent(id = 77L, source = source),
        )

        val factory = mockk<ContentRepository.Factory>()
        every { factory.create(source) } returns repo

        val savedFiltersRepo = mockk<SavedFiltersRepository>()
        coEvery { savedFiltersRepo.find(source, 42) } returns PersistableFilter(
            name = "Action",
            source = source,
            filter = filter,
        )
        coEvery { savedFiltersRepo.find(source, 999) } returns null

        val settings = mockk<AppSettings>()
        every { settings.isNsfwContentDisabled } returns false
        every { settings.globalTagBlacklist } returns emptySet()

        val fetcher = DefaultDiscoveryFeedRowFetcher(
            sourceResolutionPipeline = pipeline,
            contentRepositoryFactory = factory,
            savedFiltersRepository = savedFiltersRepo,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        val items = fetcher.fetch(testRow("r1", sourceName = "MANGADEX", savedFilterId = 42))
        assertEquals(listOf(77L), items.map { it.id })

        var missingFilterError: Throwable? = null
        try {
            fetcher.fetch(testRow("r2", sourceName = "MANGADEX", savedFilterId = 999))
        } catch (e: Throwable) {
            missingFilterError = e
        }
        assertInstanceOf(DiscoveryFeedSavedFilterNotFoundException::class.java, missingFilterError)
    }
}
