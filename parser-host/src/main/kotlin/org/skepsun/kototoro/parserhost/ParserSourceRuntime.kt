package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.IOException
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
) : SourceRuntime {
    private val requests = ParserImageRequests(platform)
    private val settings = ConcurrentHashMap<String, ParserPreferences>()

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
            handle.parser.getPages(parserChapter).mapNotNull { dataHtml(it.url) }.joinToString("\n")
                .takeIf(String::isNotBlank)?.let { SourceChapterContent(it) }
        }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = execute(content.source.name) { handle ->
        handle.parser.getRelatedContent(content.toParser(resolver(handle))).map { it.toSourceContent() }
    }

    override suspend fun fetchImage(page: SourcePage): SourceImageArtifact = execute(page.source.name) { handle ->
        val store = images ?: throw SourceOperationUnsupportedException()
        val url = if (isDirect(page.url)) page.url else
            handle.parser.getPageUrl(page.toParser(resolver(handle))).also { check(it.isNotEmpty()) { "Page url is empty" } }
        val request = requests.request(handle.parser, url, page.headers)
        val context = currentCoroutineContext()
        requests.execute(handle.parser, request).use { response ->
            if (response.code !in 200..299) throw IOException("Image response failed")
            val body = response.body
            store.materialize(handle.info.source, page.id, body.byteStream(), body.contentLength()) { context.ensureActive() }
        }
    }

    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
        execute(content.source.name) { handle ->
            val store = images ?: throw SourceOperationUnsupportedException()
            val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
                ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
            val request = requests.request(handle.parser, url, null)
            val context = currentCoroutineContext()
            requests.execute(handle.parser, request).use { response ->
                if (response.code !in 200..299) throw IOException("Image response failed")
                val body = response.body
                store.materializeCover(handle.info.source, content.id, body.byteStream(), body.contentLength()) {
                    context.ensureActive()
                }
            }
        }

    override suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = execute(sourceName) { handle ->
        val keys = ArrayList<ConfigKey<*>>().also { handle.parser.onCreateConfig(it) }
        val config = ParserSourceConfig(platform.preferences.open(ParserLoaderContext.namespace(sourceName)))
        ParserPreferences(handle.info.source, keys, config).also { settings[sourceName] = it }.screen()
    }

    override suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ): SourcePreferenceUpdate = execute(sourceName) {
        (settings[sourceName] ?: throw SourceInvalidArgumentException()).update(revision, nodeId, value)
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
