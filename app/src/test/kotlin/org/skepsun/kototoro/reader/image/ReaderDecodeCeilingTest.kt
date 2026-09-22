package org.skepsun.kototoro.reader.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Size
import coil3.size.pxOrElse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * The decode request must never be capped below the size the plan resolved.
 *
 * Regression for issue #539: a 720x7768 long-strip page is planned as `Single` (its full
 * resolution fits the policy ceiling), so the request carried no size - and the image loader's own
 * ceiling (Coil: `Extras.Key(default = Size(4096, 4096))`) silently cut the decode to 380x4096,
 * which the scene renderer then stretched across 1280x13798 screen pixels. The page was visibly
 * blurry at fit scale, on long strips only, while the legacy reader - which decodes through
 * Telephoto's own region decoder and never touches that ceiling - stayed sharp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDecodeCeilingTest {

    private val context = mockk<Context>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val requestSlot = slot<ImageRequest>()

    private val page = ReaderPage(
        id = 539L,
        url = "https://example.com/strip.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 9,
        source = TestContentSource,
    )

    private class FakePipeline(private val uri: Uri) : ComposeReaderImagePipeline {
        override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
            emit(ComposeReaderImageState.OriginalReady(uri))
        }

        override fun cachedState(pageKey: Long): ComposeReaderImageState? = null

        override suspend fun getTrimmedBounds(uri: Uri): androidx.compose.ui.unit.IntRect? = null

        override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) = Unit
    }

    private class StaticRegionSource(width: Int, height: Int) : RegionDecodeSource {
        override val metadata = ImageSourceMetadata(
            size = IntSize(width, height),
            mimeType = "image/jpeg",
            isAnimated = false,
        )
        override val geometry = ImageSourceGeometry(encodedSize = IntSize(width, height))
        override suspend fun openSession(): TileDecodeSession = mockk(relaxed = true)
    }

    private class StaticRegionFactory(private val source: RegionDecodeSource) : RegionDecoderFactory {
        override suspend fun create(
            uri: Uri,
            isAnimatedHint: Boolean?,
            geometryOverride: ImageSourceGeometry?,
        ): RegionDecodeSource = source
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.adapterFor(
        sourceWidth: Int,
        sourceHeight: Int,
        viewport: IntSize = IntSize(1080, 2400),
        capabilities: RendererCapabilities = RendererCapabilities.Unknown,
    ): KototoroImagePipelineAdapter {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/strip.jpg"
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns sourceWidth
        every { bitmap.height } returns sourceHeight
        val result = mockk<SuccessResult>()
        every { result.image } returns bitmap.asImage()
        coEvery { imageLoader.execute(capture(requestSlot)) } returns result

        return KototoroImagePipelineAdapter(
            context = context,
            composePipeline = FakePipeline(uri),
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = StaticRegionFactory(StaticRegionSource(sourceWidth, sourceHeight)),
            decodePlanner = DecodePlanner(),
            tileManager = mockk<ReaderTileManager>(relaxed = true),
            viewportSizeProvider = { viewport },
            pageLookup = { if (it.value == page.readerKey) page else null },
        ).apply { setRendererCapabilities(capabilities) }
    }

    @Test
    fun `a long strip planned at full resolution is not capped to 4096 by the loader default`() =
        runTest(testDispatcher) {
            // 720 wide at a 1080 viewport resolves sampleSize 1, and 7768 <= the 8192 policy ceiling,
            // so the plan is Single: exactly the shape that used to arrive as 380x4096.
            val adapter = adapterFor(sourceWidth = 720, sourceHeight = 7768)

            val asset = adapter.acquireAsset(PageId(page.readerKey))

            assertNotNull(asset)
            val request = requestSlot.captured
            val ceiling = request.maxBitmapSize
            assertEquals(Size(8192, 8192), ceiling)
            assertTrue(ceiling.height.pxOrElse { 0 } >= 7768) {
                "the loader ceiling $ceiling cannot hold the planned 720x7768 decode"
            }
        }

    @Test
    fun `a sampled plan taller than 4096 keeps its requested size inside the ceiling`() =
        runTest(testDispatcher) {
            // 3000x9000 at a 1080 viewport resolves SampledSingle(1500x4500). An explicit request
            // size alone is not enough: the loader's ceiling caps the decode *in addition* to it.
            val sourceWidth = 3000
            val sourceHeight = 9000
            val plan = DecodePlanner().plan(
                pageId = PageId(page.readerKey),
                metadata = ImageSourceMetadata(
                    size = IntSize(sourceWidth, sourceHeight),
                    mimeType = "image/jpeg",
                    isAnimated = false,
                ),
                geometry = ImageSourceGeometry(encodedSize = IntSize(sourceWidth, sourceHeight)),
                viewportWidth = 1080,
                viewportHeight = 2400,
            )
            val target = (plan as DecodePlan.SampledSingle).targetSize
            val adapter = adapterFor(sourceWidth = sourceWidth, sourceHeight = sourceHeight)

            adapter.acquireAsset(PageId(page.readerKey))

            val ceiling = requestSlot.captured.maxBitmapSize
            assertTrue(
                ceiling.width.pxOrElse { 0 } >= target.width && ceiling.height.pxOrElse { 0 } >= target.height,
            ) {
                "the loader ceiling $ceiling cannot hold the planned $target decode"
            }
        }

    @Test
    fun `the ceiling follows the renderer's measured limit when it is the smaller one`() =
        runTest(testDispatcher) {
            val adapter = adapterFor(
                sourceWidth = 720,
                sourceHeight = 2000,
                capabilities = RendererCapabilities.Resolved(2048, 2048),
            )

            adapter.acquireAsset(PageId(page.readerKey))

            assertEquals(Size(2048, 2048), requestSlot.captured.maxBitmapSize)
        }
}
