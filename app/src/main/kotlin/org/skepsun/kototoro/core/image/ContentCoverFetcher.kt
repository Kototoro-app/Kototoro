package org.skepsun.kototoro.core.image

import android.os.SystemClock
import android.util.Log
import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.Options
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.getAvailableRepositoryOrNull
import org.skepsun.kototoro.core.util.ext.mangaSourceKey
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import javax.inject.Inject

class ContentCoverFetcher(
    private val imageUrl: String,
    private val options: Options,
    private val imageClient: OkHttpClient,
    private val repo: ContentRepository,
    private val diskCache: DiskCache?,
    private val fetchCoordinator: ContentCoverFetchCoordinator,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = measureFetch {
        val diskCacheKey = options.diskCacheKey ?: imageUrl
        readFromDiskCache(diskCacheKey)?.let { return@measureFetch it }
        if (diskCache == null || !options.diskCachePolicy.writeEnabled) {
            return@measureFetch fetchFromNetwork()
        }
        return@measureFetch fetchCoordinator.withKeyLock(diskCacheKey) {
            readFromDiskCache(diskCacheKey) ?: fetchFromNetworkAndCache(diskCacheKey)
        }
    }

    /**
     * P2 设备验证用的封面耗时插桩：记录每次 fetch 的耗时与来源（DISK/NETWORK）或失败原因，
     * 供 logcat 过滤（tag=[TAG]）与 Perfetto 对照。
     *
     * 注意：不要用 `Log.isLoggable(tag, DEBUG)` 作守卫——Android 的 tag 默认级别是 INFO，
     * DEBUG 低于它，守卫会把这个插桩在大部分设备上静默关掉（真机验证时踩到过）。
     */
    private inline fun measureFetch(block: () -> FetchResult?): FetchResult? {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block().also { result ->
                Log.d(TAG, "ok source=${result.sourceName()} ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "cancelled ms=${SystemClock.elapsedRealtime() - startedAt}")
            throw e
        } catch (e: Throwable) {
            Log.d(
                TAG,
                "error=${e::class.simpleName} ms=${SystemClock.elapsedRealtime() - startedAt} msg=${e.message}",
            )
            throw e
        }
    }

    private fun FetchResult?.sourceName(): String = when (this) {
        is SourceFetchResult -> dataSource.name
        null -> "null"
        else -> this::class.simpleName ?: "?"
    }

    private suspend fun fetchFromNetworkAndCache(diskCacheKey: String): FetchResult {
        val response = executeNetworkRequest()
        val mimeType = response.mimeType?.toMimeTypeOrNull()?.toString()
        val editor = diskCache?.openEditor(diskCacheKey)
        if (editor == null) {
            return response.toFetchResult(mimeType)
        }
        try {
            if (mimeType != null) {
                diskCache.fileSystem.write(editor.metadata) {
                    writeUtf8(mimeType)
                }
            }
            diskCache.fileSystem.write(editor.data) {
                response.body.source().readAll(this)
            }
            response.close()
            val snapshot = checkNotNull(editor.commitAndOpenSnapshot()) {
                "Failed to open cached cover snapshot"
            }
            return snapshot.toFetchResult(mimeType, DataSource.NETWORK, diskCacheKey)
        } catch (e: Exception) {
            response.close()
            runCatching { editor.abort() }
            throw e
        }
    }

    private suspend fun fetchFromNetwork(): FetchResult {
        val response = executeNetworkRequest()
        val mimeType = response.mimeType?.toMimeTypeOrNull()?.toString()
        return response.toFetchResult(mimeType)
    }

    private suspend fun executeNetworkRequest(): okhttp3.Response {
        val request = repo.createCoverRequest(imageUrl)

        val response = try {
            imageClient.newCall(request).await()
        } catch (e: org.skepsun.kototoro.core.exceptions.CloudFlareException) {
            // Do not let InteractiveActionRequiredException bubble up to Coil EventListener
            // because it will trigger CaptchaHandler and potentially an unsolvable CDN CF loop.
            throw HttpException(
                NetworkResponse(
                    code = 403,
                    requestMillis = System.currentTimeMillis(),
                    responseMillis = System.currentTimeMillis(),
                    headers = NetworkHeaders.Builder().build(),
                    body = null,
                    delegate = okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .message("CloudFlare Protected CDN")
                        .code(403)
                        .build()
                )
            )
        }

        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.toNetworkResponse())
        }
        return response
    }

    private fun okhttp3.Response.toFetchResult(mimeType: String?): FetchResult {
        return SourceFetchResult(
            source = ImageSource(
                source = body.source(),
                fileSystem = options.fileSystem,
            ),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun readFromDiskCache(diskCacheKey: String): FetchResult? {
        if (!options.diskCachePolicy.readEnabled) return null
        val cache = diskCache ?: return null
        val snapshot = cache.openSnapshot(diskCacheKey) ?: return null
        val mimeType = runCatching {
            cache.fileSystem.read(snapshot.metadata) { readUtf8() }
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
        return snapshot.toFetchResult(mimeType ?: "image/*", DataSource.DISK, diskCacheKey)
    }

    private fun DiskCache.Snapshot.toFetchResult(
        mimeType: String?,
        dataSource: DataSource,
        diskCacheKey: String,
    ): FetchResult {
        return SourceFetchResult(
            source = ImageSource(
                file = data,
                fileSystem = diskCache!!.fileSystem,
                diskCacheKey = diskCacheKey,
                closeable = this,
            ),
            mimeType = mimeType,
            dataSource = dataSource,
        )
    }

    private val okhttp3.Response.mimeType: String?
        get() = header("Content-Type") ?: body.contentType()?.toString()

    private fun okhttp3.Response.toNetworkResponse() = NetworkResponse(
        code = code,
        requestMillis = sentRequestAtMillis,
        responseMillis = receivedResponseAtMillis,
        headers = headers.toNetworkHeaders(),
        body = body.source().let(::NetworkResponseBody),
        delegate = this,
    )

    private fun Headers.toNetworkHeaders(): NetworkHeaders {
        val headers = NetworkHeaders.Builder()
        for ((key, values) in this) {
            headers.add(key, values)
        }
        return headers.build()
    }

    private companion object {
        const val TAG = "ContentCoverFetcher"
    }

    class Factory @Inject constructor(
        private val mangaRepositoryFactory: ContentRepository.Factory,
        private val fetchCoordinator: ContentCoverFetchCoordinator,
    ) : Fetcher.Factory<CoilUri> {

        /**
         * 注意：必须声明为 [CoilUri]（而不是 String）。Coil 默认组件会把请求的 String 数据
         * 通过 `StringMapper` 先映射成 `coil3.Uri`，然后再做 fetcher 工厂解析——
         * 声明为 `Factory<String>` 时本工厂永远不会被调用（真机验证发现的死代码）。
         */
        override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme != "http" && scheme != "https") {
                return null
            }

            val mangaSource = options.extras[mangaSourceKey]?.unwrap() ?: return null
            val repo = mangaRepositoryFactory.createWithDiagnostics(mangaSource).getAvailableRepositoryOrNull(
                tag = "ContentCoverFetcher",
                prefix = "repository_unavailable",
            ) ?: run {
                Log.d(TAG, "factory reject: no repository for ${mangaSource.name}")
                return null
            }

            val imageClient = repo.getImageClient() ?: run {
                Log.d(TAG, "factory reject: no imageClient for ${mangaSource.name}")
                return null
            }

            return ContentCoverFetcher(
                imageUrl = data.toString(),
                options = options,
                imageClient = imageClient,
                repo = repo,
                diskCache = imageLoader.diskCache,
                fetchCoordinator = fetchCoordinator,
            )
        }
    }
}
