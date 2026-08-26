package org.skepsun.kototoro.core.image

import android.content.Context
import coil3.Extras
import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.toUri
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentRepositoryFactory
import org.skepsun.kototoro.core.util.ext.mangaSourceKey
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * 覆盖「封面工厂是否真的被 Coil 组件解析到」的回归测试。
 *
 * 背景：Coil 默认组件会在 fetcher 工厂解析之前把请求的 String 数据通过
 * `coil3.map.StringMapper`（internal，无法在测试中直接引用）映射成 [CoilUri]。
 * Batch 之前的实现声明为 `Fetcher.Factory<String>`，导致真实封面请求永远走不到
 * 本工厂（死代码），全部落入 Coil 默认 OkHttpNetworkFetcher。真机验证（清空磁盘
 * 缓存后仍无 ContentCoverFetcher 日志）暴露了这一问题；修复后工厂以
 * `Fetcher.Factory<CoilUri>` 匹配映射后的数据。这里直接构造与 StringMapper 输出
 * 等价的 [CoilUri] 并断言工厂接管。
 */
class ContentCoverFetcherFactoryTest {

    @Test
    fun `http CoilUri with cover extras is accepted by the factory`() {
        val factory = createFactory()

        val fetcher = factory.create(httpUri(), coverOptions(), mockk<ImageLoader>(relaxed = true))

        assertInstanceOf(ContentCoverFetcher::class.java, fetcher)
    }

    @Test
    fun `non-http CoilUri is rejected so other fetchers can handle it`() {
        val factory = createFactory()

        val fileUri = "file:///data/cover.jpg".toUri()
        val fetcher = factory.create(fileUri, coverOptions(), mockk<ImageLoader>(relaxed = true))

        assertNull(fetcher)
    }

    @Test
    fun `request without manga source extra is rejected`() {
        val factory = createFactory()

        val plainOptions = Options(
            context = mockk<Context>(relaxed = true),
            size = Size.ORIGINAL,
            scale = Scale.FIT,
            precision = Precision.EXACT,
            fileSystem = okio.FileSystem.SYSTEM,
            memoryCachePolicy = CachePolicy.ENABLED,
            diskCachePolicy = CachePolicy.ENABLED,
            networkCachePolicy = CachePolicy.ENABLED,
            extras = Extras.EMPTY,
        )

        val fetcher = factory.create(httpUri(), plainOptions, mockk<ImageLoader>(relaxed = true))

        assertNull(fetcher)
    }

    // --- helpers -------------------------------------------------------------

    private fun createFactory(): ContentCoverFetcher.Factory {
        val source = mockk<ContentSource>(relaxed = true) {
            every { name } returns "TEST"
        }
        val repository = mockk<ContentRepository>(relaxed = true) {
            every { getImageClient() } returns OkHttpClient()
        }
        val creationResult = ContentRepositoryFactory.CreationResult(
            requestedSource = source,
            resolvedSource = source,
            repository = repository,
            resolutionStatus = ContentRepositoryFactory.ResolutionStatus.UNCHANGED,
            providerStatus = ContentRepositoryFactory.ProviderStatus.SELECTED,
            cacheStatus = ContentRepositoryFactory.CacheStatus.HIT,
            selectedProvider = null,
            candidateProviders = emptyList(),
            attemptedProviders = emptyList(),
            resolutionTrace = emptyList(),
            failureReason = null,
        )
        val repositoryFactory = mockk<ContentRepository.Factory>() {
            every { createWithDiagnostics(any<ContentSource>()) } returns creationResult
        }
        return ContentCoverFetcher.Factory(
            mangaRepositoryFactory = repositoryFactory,
            fetchCoordinator = mockk<ContentCoverFetchCoordinator>(),
        )
    }

    private fun coverOptions(): Options = Options(
        context = mockk<Context>(relaxed = true),
        size = Size.ORIGINAL,
        scale = Scale.FIT,
        precision = Precision.EXACT,
        diskCacheKey = DISK_CACHE_KEY,
        fileSystem = okio.FileSystem.SYSTEM,
        memoryCachePolicy = CachePolicy.ENABLED,
        diskCachePolicy = CachePolicy.ENABLED,
        networkCachePolicy = CachePolicy.ENABLED,
        extras = Extras.Builder().set(mangaSourceKey, mockk<ContentSource>(relaxed = true) {
            every { name } returns "TEST"
        }).build(),
    )

    private fun httpUri(): CoilUri = "https://example.com/cover.jpg".toUri()

    private companion object {
        const val DISK_CACHE_KEY = "example.com/cover.jpg"
    }
}
