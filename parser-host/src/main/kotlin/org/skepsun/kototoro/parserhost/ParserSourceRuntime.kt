package org.skepsun.kototoro.parserhost

import org.skepsun.kototoro.core.source.SourceBrowserSession

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import okhttp3.Cookie

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdate
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.source.host.SourceImageStore
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Portable [SourceRuntime] over kototoro / kotatsu / Tsuki parser plugins.
 *
 * Parser sources page by offset (the number of items already shown), unlike Mihon's page index.
 */
class ParserSourceRuntime(
    private val registry: ParserPluginRegistry,
    private val platform: ParserPlatform,
    private val images: SourceImageStore? = null,
    private val ebookAssets: Path? = null,
) : SourceRuntime {
    private val requests = ParserImageRequests(platform)
    private val settings = ConcurrentHashMap<String, ParserPreferences>()
    private val settingsLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun getSources(): List<SourceRef> = registry.sources().map { it.source }

    override suspend fun describe(sourceName: String): SourceDescriptor = execute(sourceName) { handle ->
        val parser = handle.parser
        SourceDescriptor(
            source = handle.info.source,
            sortOrders = parser.availableSortOrders.mapTo(linkedSetOf()) { it.name },
            defaultSortOrder = defaultSortOrder(parser).name,
            filterCapabilities = parser.filterCapabilities.toSourceCapabilities(),
            pagingMode = SourcePagingMode.OFFSET,
            isImageFetchingSupported = images != null,
            isPreferencesSupported = true,
            isCoverFetchingSupported = images != null,
            isChapterContentSupported = handle.info.source.contentType in TEXT_CONTENT_TYPES,
            homepage = try { parser.domain.takeIf(String::isNotBlank)?.let { "https://$it" } } catch (_: Exception) { null },
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions = execute(sourceName) { handle ->
        handle.parser.getFilterOptions().toSourceFilterOptions()
    }

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> =
        execute(sourceName) { handle ->
            if (offset < 0) throw SourceInvalidArgumentException()
            val parser = handle.parser
            val sortOrder = order?.let { parserEnum<SortOrder>(it) } ?: defaultSortOrder(parser)
            if (sortOrder !in parser.availableSortOrders) throw SourceInvalidArgumentException()
            val parserFilter = filter?.toParser(resolver(handle)) ?: ContentListFilter.EMPTY
            parser.getList(offset, sortOrder, parserFilter).map { it.toSourceContent() }
        }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent =
        execute(content.source.name) { handle ->
            // Parsers have no response cache of their own, so both modes fetch fresh details.
            handle.parser.getDetails(content.toParser(resolver(handle))).toSourceContent()
        }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> =
        execute(chapter.source.name) { handle ->
            handle.parser.getPages(chapter.toParser(resolver(handle))).map { it.toSourcePage() }
        }

    override suspend fun getPageUrl(page: SourcePage): String = execute(page.source.name) { handle ->
        if (isDirect(page.url)) return@execute page.url
        handle.parser.getPageUrl(page.toParser(resolver(handle))).also { check(it.isNotEmpty()) { "Page url is empty" } }
    }

    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent? =
        execute(chapter.source.name) { handle ->
            val parserChapter = chapter.toParser(resolver(handle))
            handle.parser.getChapterContent(parserChapter)?.let { content ->
                return@execute SourceChapterContent(content.html, content.images.map { SourceContentImage(it.url, it.headers) })
            }
            // Parsers that only publish pages carry each chunk of text as an HTML data: URL.
            val pages = handle.parser.getPages(parserChapter)
            val epub = pages.singleOrNull()?.takeIf {
                it.preview.equals("EPUB", ignoreCase = true) || "EPUB" in chapter.ebookFormats ||
                    it.url.substringBefore('?').endsWith(".epub", ignoreCase = true)
            }
            if (epub != null) {
                val assets = ebookAssets ?: throw SourceOperationUnsupportedException()
                val url = handle.parser.getPageUrl(epub)
                return@execute ParserEpubContent(requests, assets).load(handle.parser, url, chapter.title.orEmpty())
            }
            pages.mapNotNull { dataHtml(it.url) }.joinToString("\n")
                .takeIf(String::isNotBlank)?.let { SourceChapterContent(it) }
        }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = execute(content.source.name) { handle ->
        handle.parser.getRelatedContent(content.toParser(resolver(handle))).map { it.toSourceContent() }
    }

    override suspend fun fetchImage(page: SourcePage): SourceImageArtifact = execute(page.source.name) { handle ->
        val store = images ?: throw SourceOperationUnsupportedException()
        val url = if (isDirect(page.url)) page.url else
            handle.parser.getPageUrl(page.toParser(resolver(handle))).also { check(it.isNotEmpty()) { "Page url is empty" } }
        val context = currentCoroutineContext()
        requests.readImage(handle.parser, url, page.headers) { input, length ->
            store.materialize(handle.info.source, page.id, input, length) { context.ensureActive() }
        }
    }

    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
        execute(content.source.name) { handle ->
            val store = images ?: throw SourceOperationUnsupportedException()
            val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
                ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
            val context = currentCoroutineContext()
            requests.readImage(handle.parser, url, null) { input, length ->
                store.materializeCover(handle.info.source, content.id, input, length) {
                    context.ensureActive()
                }
            }
        }

    override suspend fun browserSession(sourceName: String): SourceBrowserSession = execute(sourceName) { handle ->
        val domain = handle.parser.domain.takeIf(String::isNotBlank) ?: throw SourceOperationUnsupportedException()
        val userAgent = runCatching { handle.parser.getRequestHeaders()["User-Agent"] }.getOrNull()
        SourceBrowserSession("https://$domain", userAgent?.takeIf(String::isNotBlank) ?: platform.defaultUserAgent)
    }

    /**
     * Parser requests share the platform's cookie jar, so the clearance reaches every parser of that site. A login
     * made in the browser stays across restarts, as in Android's WebView store.
     */
    override suspend fun importCookies(sourceName: String, url: String, cookies: List<String>): Int =
        execute(sourceName) { _ ->
            val httpUrl = url.toHttpUrlOrNull() ?: throw SourceInvalidArgumentException()
            val parsed = cookies.mapNotNull { Cookie.parse(httpUrl, it) }
            ParserCookieJar(platform.cookieJar).saveFromResponse(httpUrl, parsed)
            parsed.size
        }

    override suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = preferenceOperation(sourceName) { handle ->
        val keys = ArrayList<ConfigKey<*>>().also { handle.parser.onCreateConfig(it) }
        val config = ParserSourceConfig(platform.preferences.open(ParserLoaderContext.namespace(sourceName)))
        ParserPreferences(handle.info.source, keys, config, handle.parser).also { settings[sourceName] = it }.screen()
    }

    override suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ): SourcePreferenceUpdate = preferenceOperation(sourceName) { handle ->
        val preferences = settings[sourceName]?.takeIf { it.owns(handle.parser) } ?: throw SourceInvalidArgumentException()
        preferences.update(revision, nodeId, value)
    }

    private suspend fun <T> preferenceOperation(sourceName: String, block: suspend (ParserHandle) -> T): T =
        execute(sourceName) { handle ->
            settingsLocks.computeIfAbsent(sourceName) { Mutex() }.withLock { block(handle) }
        }

    private suspend fun <T> execute(sourceName: String, block: suspend (ParserHandle) -> T): T {
        if (sourceName.isEmpty()) throw SourceInvalidArgumentException()
        return withContext(Dispatchers.IO) { registry.withParser(sourceName) { handle -> block(handle) } }
    }

    private fun resolver(handle: ParserHandle): (String) -> ContentSource = { name ->
        if (name == handle.parser.source.name) handle.parser.source else throw SourceInvalidArgumentException()
    }

    private fun defaultSortOrder(parser: ContentParser): SortOrder {
        val supported = parser.availableSortOrders
        return SortOrder.entries.first { it in supported }
    }

    private fun isDirect(url: String) = url.startsWith("data:") || url.startsWith("file:")

    /** The payload of a `data:` URL carrying HTML (base64 or literal), or null for any other kind of page. */
    private fun dataHtml(url: String): String? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',').takeIf { it >= 0 } ?: return null
        val payload = url.substring(comma + 1)
        return if (url.substring(5, comma).contains("base64", ignoreCase = true)) {
            try { String(Base64.getMimeDecoder().decode(payload), Charsets.UTF_8) } catch (_: IllegalArgumentException) { null }
        } else payload
    }

    companion object {
        /** Preference namespace holding one source's configuration (domain, user agent, toggles). */
        fun configNamespace(sourceName: String) = ParserLoaderContext.namespace(sourceName)

        private val TEXT_CONTENT_TYPES = setOf("NOVEL", "HENTAI_NOVEL")
    }
}
