package org.skepsun.kototoro.reader.image

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.PrefetchRequest
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderImageAssetTest {

    @Test
    fun `download failure remains observable instead of becoming an empty asset`() = runTest {
        val failure = java.net.SocketTimeoutException("Image connection timed out")
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.Failed(null, failure)
        }
        val adapter = KototoroImagePipelineAdapter(context, pipeline, imageLoader, this) { page1 }
        val pageId = PageId(page1.readerKey)

        assertNull(adapter.acquireAsset(pageId))

        val state = adapter.loadStates.value[pageId]
        assertTrue(state is ReaderImageLoadState.Failed)
        assertSame(failure, (state as ReaderImageLoadState.Failed).cause)
    }

    private val context = mockk<Context>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setUp() {
        every { imageLoader.memoryCache } returns null
        Dispatchers.setMain(testDispatcher)
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { bitmap.width } returns 640
        every { bitmap.height } returns 1280
        val result = mockk<SuccessResult>()
        every { result.image } returns bitmap.asImage()
        coEvery { imageLoader.execute(any()) } returns result
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `failed page stays failed until explicit retry then becomes drawable`() = runTest(testDispatcher) {
        val failure = java.net.SocketTimeoutException("Image connection timed out")
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.Failed(null, failure)
        }
        val adapter = KototoroImagePipelineAdapter(context, pipeline, imageLoader, this) { page1 }
        val pageId = PageId(page1.readerKey)
        assertNull(adapter.acquireAsset(pageId))
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///recovered.png"
        pipeline.stateToReturn = ComposeReaderImageState.OriginalReady(uri)

        assertNull(adapter.acquireAsset(pageId), "Scrolling must not silently retry a failed page")
        val recovered = adapter.retryAsset(pageId)
        assertTrue(recovered is ReaderImageAsset.ComposeImage)
        assertEquals(ReaderImageLoadState.Ready, adapter.loadStates.value[pageId])
        assertTrue(pipeline.lastForce)
    }

    private class FakeComposeReaderImagePipeline : ComposeReaderImagePipeline {
        var stateToReturn: ComposeReaderImageState? = null
        var lastForce = false
        var observeCount = 0

        /** When set, [observe] waits for it before emitting, standing in for a slow decode. */
        var gate: CompletableDeferred<Unit>? = null

        /** When set, [observe] reports download progress before the ready state. */
        var progress: Float? = null

        override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateToReturn

        override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
            observeCount++
            lastForce = force
            // Progress arrives before the source resolves, like the real pipeline (which reports
            // Downloading for every page whose source is not in its state cache yet).
            progress?.let { emit(ComposeReaderImageState.Downloading(it)) }
            gate?.await()
            stateToReturn?.let { emit(it) }
        }
    }

    private val page1 = ReaderPage(
        id = 100L,
        url = "https://example.com/p1.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 0,
        source = TestContentSource,
    )

    @Test
    fun `polymorphic assets retain pageId and specific representations`() {
        val pageId = PageId(123L)
        val encoded = ReaderImageAsset.Encoded(pageId, "file:///path/to/page.webp", "image/webp")
        assertEquals(pageId, encoded.pageId)
        assertEquals("file:///path/to/page.webp", encoded.uriString)
        assertEquals("image/webp", encoded.mimeType)

        val androidBitmap = ReaderImageAsset.AndroidBitmap(pageId, mockk(relaxed = true))
        assertEquals(pageId, androidBitmap.pageId)

        val composeImage = ReaderImageAsset.ComposeImage(pageId, mockk(relaxed = true))
        assertEquals(pageId, composeImage.pageId)
    }

    @Test
    fun `adapter retrieves and caches uri as encoded asset from compose pipeline`() {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "file:///cached/page1.jpg"

        val fakePipeline = FakeComposeReaderImagePipeline()
        fakePipeline.stateToReturn = ComposeReaderImageState.OriginalReady(mockUri)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = testScope,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val asset = adapter.getCachedAsset(PageId(page1.readerKey))
        assertNotNull(asset)
        assertTrue(asset is ReaderImageAsset.Encoded)
        assertEquals("file:///cached/page1.jpg", (asset as ReaderImageAsset.Encoded).uriString)

        // Replacing the resource window owns eviction; callers do not evict individual assets.
        adapter.updateResourceWindow(ReaderResourceWindow(emptyList()))
        fakePipeline.stateToReturn = null
        assertNull(adapter.getCachedAsset(PageId(page1.readerKey)))
        assertTrue(adapter.assets.value.isEmpty())
    }

    @Test
    fun `adapter observeAsset maps ComposeReaderImageState to ReaderImageAsset`() = runTest(testDispatcher) {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "file:///downloaded/page1.jpg"

        val fakePipeline = FakeComposeReaderImagePipeline()
        fakePipeline.stateToReturn = ComposeReaderImageState.OriginalReady(mockUri)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val emitted = adapter.observeAsset(PageId(page1.readerKey)).first()
        assertNotNull(emitted)
        assertTrue(emitted is ReaderImageAsset.ComposeImage)
    }

    @Test
    fun `adapter acquireAsset retrieves asset and deduplicates concurrent requests`() = runTest(testDispatcher) {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "file:///downloaded/page1.jpg"

        val fakePipeline = FakeComposeReaderImagePipeline()
        fakePipeline.stateToReturn = ComposeReaderImageState.OriginalReady(mockUri)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        // Multiple concurrent acquire calls
        val pageId = PageId(page1.readerKey)
        val deferred1 = async { adapter.acquireAsset(pageId) }
        val deferred2 = async { adapter.acquireAsset(pageId) }

        val asset1 = deferred1.await()
        val asset2 = deferred2.await()

        assertNotNull(asset1)
        assertNotNull(asset2)
        assertEquals(asset1, asset2)
        assertEquals(asset1, adapter.getCachedAsset(pageId))
    }

    @Test
    fun `source-ready window downloads source without decoding presentation image`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.MEDIUM,
                        readiness = PrefetchReadiness.SOURCE_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        // Encoded asset is cached internally in the pipeline without polluting renderer-facing assets
        assertTrue(adapter.getCachedAsset(pageId) is ReaderImageAsset.Encoded)
        assertNull(adapter.assets.value[pageId])
        coVerify(exactly = 0) { imageLoader.execute(any()) }
    }

    @Test
    fun `presentation-ready window decodes once and publishes renderer asset`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)
        val window = ReaderResourceWindow(
            listOf(
                PrefetchRequest(
                    pageId = pageId,
                    priority = PrefetchPriority.IMMEDIATE,
                    readiness = PrefetchReadiness.PRESENTATION_READY,
                ),
            ),
        )

        adapter.updateResourceWindow(window)
        adapter.updateResourceWindow(window)
        advanceUntilIdle()

        assertTrue(adapter.assets.value[pageId] is ReaderImageAsset.ComposeImage)
        assertEquals(1, fakePipeline.observeCount)
        coVerify(exactly = 1) { imageLoader.execute(any()) }
    }

    @Test
    fun `decoded dimensions are handed off before renderer publication`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)
        var rendererAssetsAtGeometryCallback: Map<PageId, ReaderImageAsset> = emptyMap()
        adapter.onAssetLoaded = { _, _ ->
            rendererAssetsAtGeometryCallback = adapter.assets.value
        }

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(
            rendererAssetsAtGeometryCallback[pageId],
            "the renderer must not observe an asset with stale scene geometry",
        )
        assertTrue(adapter.assets.value[pageId] is ReaderImageAsset.ComposeImage)
    }

    @Test
    fun `evicted in-flight decode cannot republish an asset`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val decodeGate = CompletableDeferred<Unit>()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { bitmap.width } returns 640
        every { bitmap.height } returns 1280
        val result = mockk<SuccessResult>()
        every { result.image } returns bitmap.asImage()
        coEvery { imageLoader.execute(any()) } coAnswers {
            withContext(NonCancellable) { decodeGate.await() }
            result
        }
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        adapter.updateResourceWindow(ReaderResourceWindow(emptyList()))
        decodeGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(adapter.assets.value.isEmpty())
        fakePipeline.stateToReturn = null
        assertNull(adapter.getCachedAsset(pageId))
    }

    @Test
    fun `adapter probes dimensions from Coil memoryCache without promoting to ComposeImage in getCachedAsset`() {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "file:///cached/page1.jpg"
        val fakePipeline = FakeComposeReaderImagePipeline()
        fakePipeline.stateToReturn = ComposeReaderImageState.OriginalReady(mockUri)

        val mockMemoryCache = mockk<coil3.memory.MemoryCache>()
        val mockValue = mockk<coil3.memory.MemoryCache.Value>()
        val mockBitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 800
        every { mockBitmap.height } returns 1200
        val mockImage = mockBitmap.asImage()
        every { mockValue.image } returns mockImage
        every { mockMemoryCache.get(coil3.memory.MemoryCache.Key("file:///cached/page1.jpg")) } returns mockValue
        every { imageLoader.memoryCache } returns mockMemoryCache

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = testScope,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        val probed = adapter.probeCachedDimensions(pageId)
        assertNotNull(probed)
        assertEquals(800, probed?.width)
        assertEquals(1200, probed?.height)

        val asset = adapter.getCachedAsset(pageId)
        assertNotNull(asset)
        assertTrue(asset is ReaderImageAsset.Encoded, "getCachedAsset must NOT promote memoryCache entry to ComposeImage")
        assertNull(adapter.assets.value[pageId], "Must not contaminate renderer presentation assets")
    }

    @Test
    fun `window transition downgrades presentation asset to source asset when page leaves presentation window`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)

        // Step 1: PRESENTATION_READY -> Decodes into ComposeImage
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(adapter.assets.value[pageId] is ReaderImageAsset.ComposeImage)
        assertTrue(adapter.getCachedAsset(pageId) is ReaderImageAsset.ComposeImage)

        // Step 2: Downgrade to SOURCE_READY -> Presentation asset released from StateFlow, downgraded to Encoded in cache
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.MEDIUM,
                        readiness = PrefetchReadiness.SOURCE_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertNull(adapter.assets.value[pageId], "Presentation asset must be dropped from renderer-facing assets")
        val cached = adapter.getCachedAsset(pageId)
        assertTrue(cached is ReaderImageAsset.Encoded, "Cache must be downgraded to lightweight Encoded handle")
        assertEquals("file:///downloaded/page1.jpg", (cached as ReaderImageAsset.Encoded).uriString)

        // Step 3: Upgrade back to PRESENTATION_READY -> Upgraded to ComposeImage
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(adapter.assets.value[pageId] is ReaderImageAsset.ComposeImage)
    }

    @Test
    fun `downgraded page rejects late-finishing in-flight decode from resurrecting into presentation assets`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val decodeGate = CompletableDeferred<Unit>()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { bitmap.width } returns 640
        every { bitmap.height } returns 1280
        val result = mockk<SuccessResult>()
        every { result.image } returns bitmap.asImage()
        coEvery { imageLoader.execute(any()) } coAnswers {
            withContext(NonCancellable) { decodeGate.await() }
            result
        }
        val fakePipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )
        val pageId = PageId(page1.readerKey)

        // Request PRESENTATION_READY -> start background decode
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )

        // Before decode completes, page is downgraded to SOURCE_READY
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.MEDIUM,
                        readiness = PrefetchReadiness.SOURCE_READY,
                    ),
                ),
            ),
        )
        decodeGate.complete(Unit)
        advanceUntilIdle()

        // Presentation asset must NOT resurrect into renderer assets
        assertNull(adapter.assets.value[pageId])
        // Cache holds at most the Encoded source
        assertTrue(adapter.getCachedAsset(pageId) is ReaderImageAsset.Encoded)
    }

    @Test
    fun `getCachedAsset returns Encoded when page readiness is SOURCE_READY even if Coil memoryCache has bitmap`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        val uriString = "file:///downloaded/page1.jpg"
        every { uri.toString() } returns uriString

        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        val memoryCache = mockk<coil3.memory.MemoryCache>()
        val memoryImage = bitmap.asImage()
        every { memoryCache[coil3.memory.MemoryCache.Key(uriString)] } returns coil3.memory.MemoryCache.Value(memoryImage)
        every { imageLoader.memoryCache } returns memoryCache

        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            pageLookup = { page1 },
        )

        val pageId = PageId(page1.readerKey)
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.MEDIUM,
                        readiness = PrefetchReadiness.SOURCE_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val cached = adapter.getCachedAsset(pageId)
        assertTrue(cached is ReaderImageAsset.Encoded, "Should return Encoded when readiness is SOURCE_READY")
        assertNull(adapter.assets.value[pageId], "Must not populate renderer-facing assets")
    }

    @Test
    fun `warm backend switch from legacy with low-res memoryCache entry does not pollute presentation assets`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        val uriString = "file:///downloaded/page1.jpg"
        every { uri.toString() } returns uriString

        val lowResBitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { lowResBitmap.width } returns 160
        every { lowResBitmap.height } returns 480
        val memoryCache = mockk<coil3.memory.MemoryCache>()
        every { memoryCache[coil3.memory.MemoryCache.Key(uriString)] } returns coil3.memory.MemoryCache.Value(lowResBitmap.asImage())
        every { imageLoader.memoryCache } returns memoryCache

        val highResBitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { highResBitmap.width } returns 1080
        every { highResBitmap.height } returns 3240
        val successResult = mockk<coil3.request.SuccessResult>()
        every { successResult.image } returns highResBitmap.asImage()
        coEvery { imageLoader.execute(any()) } returns successResult

        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { page1 },
        )

        val pageId = PageId(page1.readerKey)

        // 1. Probing cached dimensions for scene hints returns geometry without polluting assets
        val probed = adapter.probeCachedDimensions(pageId)
        assertEquals(160, probed?.width)
        assertEquals(480, probed?.height)
        assertNull(adapter.assets.value[pageId], "Probing dimensions must NEVER populate presentation assets")

        // 2. getCachedAsset returns Encoded source, not the low-res ComposeImage
        val cached = adapter.getCachedAsset(pageId)
        assertTrue(cached is ReaderImageAsset.Encoded)

        // 3. When page enters PRESENTATION_READY, it must execute full decode (acquireAsset)
        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        // 4. Assert full-res presentation asset is now loaded
        val presentationAsset = adapter.assets.value[pageId]
        assertTrue(presentationAsset is ReaderImageAsset.ComposeImage, "Authoritative presentation asset should be loaded")
    }

    @Test
    fun `a decode that finishes quickly never publishes a loading state`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { page1 },
        )
        val pageId = PageId(page1.readerKey)
        val seen = mutableListOf<ReaderImageLoadState?>()
        val collector = launch(testDispatcher) { adapter.loadStates.collect { seen += it[pageId] } }

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        collector.cancel()

        assertTrue(adapter.assets.value[pageId] is ReaderImageAsset.ComposeImage)
        assertTrue(
            seen.none { it is ReaderImageLoadState.Loading },
            "a page that is already cached must not report loading, otherwise every warm page turn flashes a spinner: $seen",
        )
    }

    @Test
    fun `a decode that takes longer than the delay does publish a loading state`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val gate = CompletableDeferred<Unit>()
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
            this.gate = gate
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { page1 },
        )
        val pageId = PageId(page1.readerKey)

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        advanceTimeBy(500)
        runCurrent()

        assertTrue(
            adapter.loadStates.value[pageId] is ReaderImageLoadState.Loading,
            "a genuinely slow page still has to report that it is loading",
        )

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ReaderImageLoadState.Ready, adapter.loadStates.value[pageId])
    }

    @Test
    fun `download progress does not announce loading while the page is still loading`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///downloaded/page1.jpg"
        val gate = CompletableDeferred<Unit>()
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
            progress = 0.5f
            this.gate = gate
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            pageLookup = { page1 },
        )
        val pageId = PageId(page1.readerKey)

        adapter.updateResourceWindow(
            ReaderResourceWindow(
                listOf(
                    PrefetchRequest(
                        pageId = pageId,
                        priority = PrefetchPriority.IMMEDIATE,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                    ),
                ),
            ),
        )
        runCurrent()

        val stateWhileLoading = adapter.loadStates.value[pageId]
        assertTrue(
            stateWhileLoading !is ReaderImageLoadState.Loading,
            "progress reported by a page that is still loading must not announce a spinner before the " +
                "delay, otherwise every page of a continuous turn run flashes 加载中 (state=$stateWhileLoading)",
        )

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ReaderImageLoadState.Ready, adapter.loadStates.value[pageId])
    }
}
