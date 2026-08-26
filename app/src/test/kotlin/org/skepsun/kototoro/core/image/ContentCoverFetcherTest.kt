package org.skepsun.kototoro.core.image

import android.content.Context
import coil3.Extras
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.parser.ContentRepository
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ContentCoverFetcherTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `concurrent displays share one cover download and both receive the image`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(delayedImageResponse())
            server.enqueue(delayedImageResponse())
            val imageUrl = server.url("/cover.jpg").toString()
            val repository = coverRepository(imageUrl)
            val client = OkHttpClient()
            val diskCache = TrackingDiskCache(createDiskCache())
            val coordinator = ContentCoverFetchCoordinator()

            val first = async(Dispatchers.IO) {
                createFetcher(imageUrl, createOptions(), client, repository, diskCache, coordinator).fetch().readBody()
            }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) {
                createFetcher(imageUrl, createOptions(), client, repository, diskCache, coordinator).fetch().readBody()
            }

            assertEquals(IMAGE_BODY, first.await())
            assertEquals(IMAGE_BODY, second.await())
            assertEquals(1, server.requestCount)
            // Every snapshot that was opened by the shared download must have been closed.
            assertEquals(diskCache.openedSnapshots, diskCache.closedSnapshots)
        }
    }

    @Test
    fun `second request after first download returns from coil disk cache`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(delayedImageResponse())
            val imageUrl = server.url("/cover.jpg").toString()
            val repository = coverRepository(imageUrl)
            val bodyTracker = TrackingBodyInterceptor()
            val client = OkHttpClient.Builder().addInterceptor(bodyTracker.interceptor()).build()
            val diskCache = TrackingDiskCache(createDiskCache())
            val coordinator = ContentCoverFetchCoordinator()

            val first = createFetcher(
                imageUrl, createOptions(), client, repository, diskCache, coordinator,
            ).fetch()
            assertEquals(IMAGE_BODY, first.readBody())
            // The network response body must be closed after being written to the disk cache.
            assertEquals(1, bodyTracker.closedCount)

            val second = createFetcher(
                imageUrl, createOptions(), client, repository, diskCache, coordinator,
            ).fetch()
            val result = second as SourceFetchResult
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(IMAGE_BODY, result.readBody())

            assertEquals(1, server.requestCount)
            assertEquals(diskCache.openedSnapshots, diskCache.closedSnapshots)
        }
    }

    @Test
    fun `cancelling an in-flight fetch cancels the underlying okhttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(delayedImageResponse())
            val imageUrl = server.url("/cover.jpg").toString()
            val repository = coverRepository(imageUrl)
            val cancelledCalls = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder()
                .eventListenerFactory {
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            cancelledCalls.add(call.request().url.toString())
                        }
                    }
                }
                .build()
            val diskCache = TrackingDiskCache(createDiskCache())
            val coordinator = ContentCoverFetchCoordinator()

            val job = async(Dispatchers.IO) {
                createFetcher(imageUrl, createOptions(), client, repository, diskCache, coordinator).fetch()
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancelAndJoin()

            pollUntil { cancelledCalls.contains(imageUrl) }

            // A later retry after the cancelled request must still work and not be poisoned.
            server.enqueue(delayedImageResponse())
            val retry = createFetcher(
                imageUrl, createOptions(), client, repository, diskCache, coordinator,
            ).fetch()
            assertEquals(IMAGE_BODY, retry.readBody())
            assertEquals(2, server.requestCount)
            assertEquals(diskCache.openedSnapshots, diskCache.closedSnapshots)
        }
    }

    @Test
    fun `first network failure does not poison later normal retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.enqueue(delayedImageResponse())
            val imageUrl = server.url("/cover.jpg").toString()
            val repository = coverRepository(imageUrl)
            val diskCache = TrackingDiskCache(createDiskCache())
            val coordinator = ContentCoverFetchCoordinator()

            val error = runCatching {
                createFetcher(
                    imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator,
                ).fetch()
            }.exceptionOrNull()
            assertTrue(error is HttpException, "expected HttpException but was $error")

            val retry = createFetcher(
                imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator,
            ).fetch()
            assertEquals(IMAGE_BODY, retry.readBody())

            // A third consumer is now served from the disk cache written by the successful retry.
            val cached = createFetcher(
                imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator,
            ).fetch() as SourceFetchResult
            assertEquals(DataSource.DISK, cached.dataSource)
            assertEquals(IMAGE_BODY, cached.readBody())

            assertEquals(2, server.requestCount)
            assertEquals(diskCache.openedSnapshots, diskCache.closedSnapshots)
        }
    }

    @Test
    fun `cancelling one waiter does not fail the shared download`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(delayedImageResponse())
            val imageUrl = server.url("/cover.jpg").toString()
            val repository = coverRepository(imageUrl)
            val diskCache = TrackingDiskCache(createDiskCache())
            val coordinator = ContentCoverFetchCoordinator()

            val first = async(Dispatchers.IO) {
                createFetcher(imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator).fetch()
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            // A second consumer arrives while the first download is still in flight and then leaves.
            val waiter = async(Dispatchers.IO) {
                createFetcher(imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator).fetch()
            }
            delay(50)
            waiter.cancelAndJoin()

            // The remaining consumer still receives the shared download.
            assertEquals(IMAGE_BODY, first.await().readBody())

            // A later consumer is served from disk without triggering a second download.
            val later = createFetcher(
                imageUrl, createOptions(), OkHttpClient(), repository, diskCache, coordinator,
            ).fetch() as SourceFetchResult
            assertEquals(DataSource.DISK, later.dataSource)
            assertEquals(IMAGE_BODY, later.readBody())

            assertEquals(1, server.requestCount)
            assertEquals(diskCache.openedSnapshots, diskCache.closedSnapshots)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun coverRepository(imageUrl: String): ContentRepository = mockk {
        every { createCoverRequest(imageUrl) } returns Request.Builder().url(imageUrl).build()
    }

    private fun createFetcher(
        imageUrl: String,
        options: Options,
        client: OkHttpClient,
        repository: ContentRepository,
        diskCache: DiskCache,
        coordinator: ContentCoverFetchCoordinator,
    ) = ContentCoverFetcher(
        imageUrl = imageUrl,
        options = options,
        imageClient = client,
        repo = repository,
        diskCache = diskCache,
        fetchCoordinator = coordinator,
    )

    private fun createOptions() = Options(
        context = mockk<Context>(relaxed = true),
        size = Size.ORIGINAL,
        scale = Scale.FIT,
        precision = Precision.EXACT,
        diskCacheKey = DISK_CACHE_KEY,
        fileSystem = FileSystem.SYSTEM,
        memoryCachePolicy = CachePolicy.ENABLED,
        diskCachePolicy = CachePolicy.ENABLED,
        networkCachePolicy = CachePolicy.ENABLED,
        extras = Extras.EMPTY,
    )

    private fun createDiskCache() = DiskCache.Builder()
        .directory(tempDir.toOkioPath())
        .maxSizeBytes(1024 * 1024)
        .build()

    private suspend fun Any?.readBody(): String = withContext(Dispatchers.IO) {
        val result = this@readBody as SourceFetchResult
        result.source.use { source ->
            source.source().readUtf8()
        }
    }

    private fun delayedImageResponse() = MockResponse()
        .setHeader("Content-Type", "image/jpeg")
        .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        .setBody(IMAGE_BODY)

    private fun pollUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    private companion object {
        const val IMAGE_BODY = "fake-image"
        const val DISK_CACHE_KEY = "shared-cover#test"
    }
}

/** Delegating [DiskCache] that counts how many non-null snapshots were opened vs closed. */
private class TrackingDiskCache(
    private val delegate: DiskCache,
) : DiskCache {

    var openedSnapshots: Int = 0
        private set
    var closedSnapshots: Int = 0
        private set

    override val size: Long
        get() = delegate.size
    override val maxSize: Long
        get() = delegate.maxSize
    override val directory: Path
        get() = delegate.directory
    override val fileSystem: FileSystem
        get() = delegate.fileSystem

    override fun openSnapshot(key: String): DiskCache.Snapshot? {
        val snapshot = delegate.openSnapshot(key) ?: return null
        openedSnapshots++
        return object : DiskCache.Snapshot by snapshot {
            override fun close() {
                closedSnapshots++
                snapshot.close()
            }
        }
    }

    override fun openEditor(key: String): DiskCache.Editor? = delegate.openEditor(key)

    override fun remove(key: String): Boolean = delegate.remove(key)

    override fun clear() = delegate.clear()

    override fun shutdown() = delegate.shutdown()
}

/** OkHttp interceptor that records how many network response bodies were closed. */
private class TrackingBodyInterceptor {

    var closedCount: Int = 0
        private set

    fun interceptor(): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val originalBody = requireNotNull(response.body)
        val trackedBody = object : ResponseBody() {
            override fun contentType(): MediaType? = originalBody.contentType()
            override fun contentLength(): Long = originalBody.contentLength()
            override fun source(): BufferedSource = originalBody.source()
            override fun close() {
                closedCount++
                originalBody.close()
            }
        }
        response.newBuilder().body(trackedBody).build()
    }
}
