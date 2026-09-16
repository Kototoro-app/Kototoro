package org.skepsun.kototoro.reader.image

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderImageAssetTest {

    private val context = mockk<Context>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class FakeComposeReaderImagePipeline : ComposeReaderImagePipeline {
        var stateToReturn: ComposeReaderImageState? = null

        override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateToReturn

        override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
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

        // Evict removes from in-memory cache
        adapter.evictAsset(PageId(page1.readerKey))
        fakePipeline.stateToReturn = null
        assertNull(adapter.getCachedAsset(PageId(page1.readerKey)))
    }

    @Test
    fun `adapter observeAsset maps ComposeReaderImageState to ReaderImageAsset`() = testScope.run {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "file:///downloaded/page1.jpg"

        val fakePipeline = FakeComposeReaderImagePipeline()
        fakePipeline.stateToReturn = ComposeReaderImageState.OriginalReady(mockUri)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = fakePipeline,
            imageLoader = imageLoader,
            scope = testScope,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val emitted = kotlinx.coroutines.runBlocking {
            adapter.observeAsset(PageId(page1.readerKey)).first()
        }
        assertNotNull(emitted)
        assertTrue(emitted is ReaderImageAsset.Encoded)
        assertEquals("file:///downloaded/page1.jpg", (emitted as ReaderImageAsset.Encoded).uriString)
    }
}
