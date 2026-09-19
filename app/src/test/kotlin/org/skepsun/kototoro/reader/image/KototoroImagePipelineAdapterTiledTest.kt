package org.skepsun.kototoro.reader.image

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.PrefetchRequest
import org.skepsun.kototoro.reader.core.ReaderCameraSnapshot
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

@OptIn(ExperimentalCoroutinesApi::class)
class KototoroImagePipelineAdapterTiledTest {

    private val context = mockk<Context>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val page1 = ReaderPage(
        id = 101L,
        url = "https://example.com/strip.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 0,
        source = TestContentSource,
    )

    private val pageLeft = ReaderPage(
        id = 102L,
        url = "https://example.com/double.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 1,
        source = TestContentSource,
        split = ReaderPageSplit.LEFT,
    )

    private val pageRight = ReaderPage(
        id = 103L,
        url = "https://example.com/double.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 2,
        source = TestContentSource,
        split = ReaderPageSplit.RIGHT,
    )

    private class FakeComposeReaderImagePipeline : ComposeReaderImagePipeline {
        var stateToReturn: ComposeReaderImageState? = null
        var trimmedBoundsToReturn: androidx.compose.ui.unit.IntRect? = null
        var reportedDimensions: Triple<ReaderPage, Int, Int>? = null

        override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
            stateToReturn?.let { emit(it) }
        }

        override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateToReturn

        override suspend fun getTrimmedBounds(uri: Uri): androidx.compose.ui.unit.IntRect? = trimmedBoundsToReturn

        override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) {
            reportedDimensions = Triple(page, width, height)
        }
    }

    private class FakeRegionDecodeSource(
        override val metadata: ImageSourceMetadata = ImageSourceMetadata(
            size = IntSize(800, 32000),
            mimeType = "image/jpeg",
            isAnimated = false,
        ),
        override val geometry: ImageSourceGeometry = ImageSourceGeometry(
            encodedSize = IntSize(800, 32000),
        ),
    ) : RegionDecodeSource {
        override suspend fun openSession(): TileDecodeSession = mockk(relaxed = true)
    }

    private class FakeRegionDecoderFactory(
        val sourceToReturn: FakeRegionDecodeSource = FakeRegionDecodeSource(),
    ) : RegionDecoderFactory {
        var shouldFail = false
        var createdWithUri: Uri? = null

        override suspend fun create(
            uri: Uri,
            isAnimatedHint: Boolean?,
            geometryOverride: ImageSourceGeometry?,
        ): RegionDecodeSource {
            if (shouldFail) throw java.io.IOException("Decoder creation failed")
            createdWithUri = uri
            return sourceToReturn
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        every { bitmap.width } returns 800
        every { bitmap.height } returns 1200
        val result = mockk<SuccessResult>()
        every { result.image } returns bitmap.asImage()
        coEvery { imageLoader.execute(any()) } returns result
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `toTileSplit converts ReaderPageSplit correctly`() {
        assertEquals(TileSplit.NONE, ReaderPageSplit.NONE.toTileSplit())
        assertEquals(TileSplit.LEFT, ReaderPageSplit.LEFT.toTileSplit())
        assertEquals(TileSplit.RIGHT, ReaderPageSplit.RIGHT.toTileSplit())
    }

    @Test
    fun `acquireAsset returns Tiled asset when planner plans Tiled`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/webtoon_strip.jpg"

        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }

        val regionFactory = FakeRegionDecoderFactory()
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)
        val planner = DecodePlanner()

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            decodePlanner = planner,
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1080, 2400) },
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        val asset = adapter.acquireAsset(pageId)

        assertNotNull(asset)
        assertTrue(asset is ReaderImageAsset.Tiled)
        val tiled = asset as ReaderImageAsset.Tiled
        assertEquals(pageId, tiled.pageId)
        assertEquals(800, tiled.grid.pageSize.width)
        assertEquals(32000, tiled.grid.pageSize.height)
        assertSame(mockTileManager, tiled.tileStore)

        // Verifies overview requested
        verify { mockTileManager.requestOverview(tiled.grid, any()) }

        // Pipeline dimensions reported
        assertEquals(page1, pipeline.reportedDimensions?.first)
        assertEquals(800, pipeline.reportedDimensions?.second)
        assertEquals(32000, pipeline.reportedDimensions?.third)
    }

    @Test
    fun `split pages map split correctly to TileGrid`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/double_spread.jpg"

        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }

        val regionFactory = FakeRegionDecoderFactory(
            FakeRegionDecodeSource(
                metadata = ImageSourceMetadata(size = IntSize(1600, 32000), mimeType = "image/jpeg"),
                geometry = ImageSourceGeometry(encodedSize = IntSize(1600, 32000)),
            ),
        )
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            decodePlanner = DecodePlanner(),
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1080, 2400) },
            pageLookup = { id ->
                when (id.value) {
                    pageLeft.readerKey -> pageLeft
                    pageRight.readerKey -> pageRight
                    else -> null
                }
            },
        )

        val leftAsset = adapter.acquireAsset(PageId(pageLeft.readerKey)) as ReaderImageAsset.Tiled
        assertEquals(TileSplit.LEFT, leftAsset.grid.split)
        assertEquals(800, leftAsset.grid.pageSize.width)

        val rightAsset = adapter.acquireAsset(PageId(pageRight.readerKey)) as ReaderImageAsset.Tiled
        assertEquals(TileSplit.RIGHT, rightAsset.grid.split)
        assertEquals(800, rightAsset.grid.pageSize.width)
    }

    @Test
    fun `requestTiles forwards to tileManager`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/strip.jpg"
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = FakeRegionDecoderFactory(),
            decodePlanner = DecodePlanner(),
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1080, 2400) },
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        val asset = adapter.acquireAsset(pageId) as ReaderImageAsset.Tiled
        val visible = IntRect.fromLtwh(0, 500, 800, 1500)
        val lookahead = IntRect.fromLtwh(0, 0, 800, 3000)

        adapter.requestTiles(pageId, visible, lookahead)
        verify { mockTileManager.requestTiles(asset.grid, visible, lookahead) }
    }

    @Test
    fun `evictAsset releases page from tileManager`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/strip.jpg"
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = FakeRegionDecoderFactory(),
            decodePlanner = DecodePlanner(),
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1080, 2400) },
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        adapter.acquireAsset(pageId)
        assertNotNull(adapter.assets.value[pageId])

        // Update resource window with an empty set to trigger eviction
        adapter.updateResourceWindow(ReaderResourceWindow(emptyList()))

        verify { mockTileManager.releasePage(pageId) }
        assertNull(adapter.assets.value[pageId])
    }

    @Test
    fun `a page acquired under magnification keeps a fit-level base`() = runTest(testDispatcher) {
        // A page is usually acquired while the camera is already magnified (a turn during a zoom), and
        // sizing its base from that camera is what painted a page at fit scale from level zero: 43
        // tiles / 172MB per layer, CPU P99 12.1ms at 2x against 8ms for the same page as a bitmap.
        // The base is the *fit* level here and the camera's level lives in the target layer.
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/oversized.jpg"
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val regionFactory = FakeRegionDecoderFactory(
            FakeRegionDecodeSource(
                metadata = ImageSourceMetadata(size = IntSize(6000, 9000), mimeType = "image/jpeg"),
                geometry = ImageSourceGeometry(encodedSize = IntSize(6000, 9000)),
            ),
        )
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            decodePlanner = DecodePlanner(),
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1280, 2772) },
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        adapter.onCameraSettled(
            ReaderCameraSnapshot(
                scale = 4f,
                visibleBoundsInScene = FloatRect(0f, 0f, 1280f, 2772f),
            ),
        )

        val pageId = PageId(page1.readerKey)
        val asset = adapter.acquireAsset(pageId) as ReaderImageAsset.Tiled

        assertEquals(4, asset.base.sampleSize, "base must be the fit level, not the camera's")
        assertEquals(1, asset.target?.sampleSize, "the camera's own level belongs in the target")
        assertEquals(IntSize(6000, 9000), asset.target?.grid?.pageSize)
        // The lattice stays small even at the camera's level: enlarging tiles to about a viewport each
        // cut the painted count 4x but raised CPU P99 from 12.1ms to 29.8ms on the same fixture.
        assertEquals(1024, asset.target?.grid?.tileDimension?.width)

        // Back at fit the target is withdrawn; the base was already right.
        adapter.onCameraSettled(
            ReaderCameraSnapshot(
                scale = 1f,
                visibleBoundsInScene = FloatRect(0f, 0f, 1280f, 2772f),
            ),
        )
        val settled = adapter.assets.value[pageId] as ReaderImageAsset.Tiled
        assertEquals(4, settled.base.sampleSize)
        assertNull(settled.target)
    }

    @Test
    fun `fallback to standard single image when factory throws`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/normal.jpg"
        val pipeline = FakeComposeReaderImagePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri)
        }
        val regionFactory = FakeRegionDecoderFactory().apply {
            shouldFail = true
        }
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            decodePlanner = DecodePlanner(),
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(1080, 2400) },
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        val asset = adapter.acquireAsset(pageId)

        assertTrue(asset is ReaderImageAsset.ComposeImage)
    }
}
