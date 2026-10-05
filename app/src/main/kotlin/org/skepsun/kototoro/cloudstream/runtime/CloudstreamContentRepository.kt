package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers

/**
 * Android's repository over the shared [CloudstreamCatalog]: it adds the content cache, Android logging, Cloudflare
 * exceptions with the WebView resolver (TRANSPORT strategy only), and the related-content search fallback.
 */
class CloudstreamContentRepository(
    override val source: CloudstreamSource,
    cache: MemoryContentCache,
    private val webViewExecutor: WebViewExecutor,
    private val cookieJar: MutableCookieJar,
    private val settings: AppSettings,
) : CachingContentRepository(cache) {
    private val catalog = CloudstreamCatalog(source, AndroidCloudstreamPlatform())

    override val listPagingMode: ContentRepository.ListPagingMode = ContentRepository.ListPagingMode.PAGE_INDEX

    override val sortOrders: Set<SortOrder> = catalog.sortOrders

    override var defaultSortOrder: SortOrder = SortOrder.RELEVANCE

    override val filterCapabilities: ContentListFilterCapabilities = catalog.filterCapabilities

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> =
        catalog.getList(offset, filter)

    override suspend fun getDetailsImpl(manga: Content): Content = catalog.getDetails(manga)

    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> =
        catalog.getPages(chapter)

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    internal fun getPlaybackEvents(
        chapter: ContentChapter,
        clearCache: Boolean = false,
    ): Flow<CloudstreamPlaybackEvent> = catalog.playbackEvents(chapter, clearCache)

    override suspend fun getFilterOptions(): ContentListFilterOptions = catalog.getFilterOptions()

    override suspend fun getRelatedContentImpl(seed: Content): List<Content> {
        catalog.getRecommendations(seed).takeIf { it.isNotEmpty() }?.let { return it }
        return RelatedContentSearchFallback.find(seed) { query ->
            getList(offset = 0, order = defaultSortOrder, filter = ContentListFilter(query = query))
        }
    }

    private inner class AndroidCloudstreamPlatform : CloudstreamPlatform {
        override val isDebug: Boolean get() = BuildConfig.DEBUG
        override fun debug(message: String) { Log.d(TAG, message) }
        override fun warn(message: String, error: Throwable?) { Log.w(TAG, message, error) }
        override fun error(message: String, error: Throwable?) { Log.e(TAG, message, error) }

        override suspend fun <T> withSource(source: CloudstreamSource, block: suspend () -> T): T =
            CloudstreamRequestContext.withSource(source, block)

        override suspend fun <T> withLoadLinks(block: suspend () -> T): CloudstreamLoadLinksExecution<T> =
            CloudstreamRequestContext.withLoadLinksCompatibility { block() }
                .let { CloudstreamLoadLinksExecution(it.value, it.challenge) }

        override fun findChallenge(error: Throwable, source: CloudstreamSource): Throwable? =
            error.findCloudFlareException()?.withCloudstreamSource(error)

        override suspend fun resolveChallenge(challenge: Throwable, url: String, stage: String): Boolean {
            if (settings.cloudflareStrategy != CloudflareStrategy.TRANSPORT) {
                Log.w(
                    TAG,
                    "$stage webview transport not selected (${settings.cloudflareStrategy}); " +
                        "skipping cloudstream auto resolve url=$url",
                )
                return false
            }
            val error = challenge as? CloudFlareException ?: return false
            val resolved = webViewExecutor.tryResolveCaptcha(error, timeout = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS)
            Log.w(TAG, "$stage cloudflare resolve result source=${source.displayName} url=$url resolved=$resolved " +
                "cookies=${cookieSummary(url)}")
            return resolved
        }

        override fun cookieSummary(url: String): String {
            val httpUrl = url.toHttpUrlOrNull() ?: return "invalid-url"
            val cookies = runCatching { cookieJar.loadForRequest(httpUrl) }.getOrElse { return "error=${it::class.simpleName}" }
            if (cookies.isEmpty()) return "count=0 names=[] hasCfClearance=false"
            return "count=${cookies.size} names=${cookies.map { it.name }} hasCfClearance=${cookies.any { it.name == "cf_clearance" }}"
        }

        override suspend fun diagnoseEmptyMainPage(source: CloudstreamSource, request: MainPageRequest, slot: Int, page: Int) {
            if (settings.cloudflareStrategy != CloudflareStrategy.TRANSPORT) return
            val diagnosticUrl = request.data.takeIf { it.isNotBlank() } ?: source.api.mainUrl
            val result = runCatchingCancellable {
                webViewExecutor.fetchWithBrowserContext(
                    url = diagnosticUrl,
                    userAgent = CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent,
                    allowInteractiveChallenge = false,
                    settleDelayMs = 2_000,
                    timeoutMs = 15_000,
                )
            }.onFailure { Log.e(TAG, "main page browserContext failed source=${source.displayName}", it) }
                .getOrNull() ?: return
            Log.w(
                TAG,
                "main page browserContext source=${source.displayName} requestName=${request.name} slot=$slot " +
                    "page=$page status=${result.status} finalUrl=${result.url} bodyLength=${result.body.length} " +
                    "cookies=${cookieSummary(diagnosticUrl)} bodyPreview=${result.body.replace(Regex("\\s+"), " ").take(1_000)}",
            )
        }
    }

    private fun CloudFlareException.withCloudstreamSource(cause: Throwable): CloudFlareException {
        val headers = (this as? CloudFlareProtectedException)?.headers ?: Headers.Builder().build()
        val enriched = CloudFlareProtectedException(
            url = url,
            source = source,
            headers = headers.newBuilder()
                .apply {
                    (CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent)?.takeIf { it.isNotBlank() }?.let {
                        set(CommonHeaders.USER_AGENT, it)
                    }
                }
                .set(CommonHeaders.MANGA_SOURCE, source.name)
                .build(),
        )
        if (cause !== this) enriched.addSuppressed(cause)
        return enriched
    }

    private companion object {
        const val TAG = "CloudstreamRepo"
    }
}
