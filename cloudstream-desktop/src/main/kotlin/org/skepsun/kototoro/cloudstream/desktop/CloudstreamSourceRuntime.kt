package org.skepsun.kototoro.cloudstream.desktop

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamArtworkHeaders
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamMetadataCodec
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.parserhost.toParser
import org.skepsun.kototoro.parserhost.toSourceCapabilities
import org.skepsun.kototoro.parserhost.toSourceContent
import org.skepsun.kototoro.parserhost.toSourceFilterOptions
import org.skepsun.kototoro.parserhost.toSourcePage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.source.host.SourceImageStore
import java.io.IOException

/**
 * Portable [SourceRuntime] over Cloudstream providers: the shared [org.skepsun.kototoro.cloudstream.runtime.CloudstreamCatalog]
 * projected to the source protocol like parser sources (same parser model). Lists page by index (offset 0 is page 1);
 * a blank query lists the home sections, which are also offered as one exclusive tag group. Episodes resolve to the
 * links the plugin found, each with its headers and subtitles, for the video player.
 */
class CloudstreamSourceRuntime(
    private val registry: CloudstreamPluginRegistry,
    private val images: SourceImageStore? = null,
) : SourceRuntime {
    override suspend fun getSources(): List<SourceRef> = registry.sources().map(::sourceRef)

    fun owns(sourceName: String): Boolean = registry.owns(sourceName)

    override suspend fun describe(sourceName: String): SourceDescriptor = execute(sourceName) { source ->
        val catalog = registry.catalog(source.name)
        SourceDescriptor(
            source = sourceRef(source),
            sortOrders = catalog.sortOrders.mapTo(linkedSetOf()) { it.name },
            defaultSortOrder = catalog.sortOrders.first().name,
            filterCapabilities = catalog.filterCapabilities.toSourceCapabilities(),
            pagingMode = SourcePagingMode.PAGE_INDEX,
            isCoverFetchingSupported = images != null,
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions = execute(sourceName) { source ->
        registry.catalog(source.name).getFilterOptions().toSourceFilterOptions()
    }

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> =
        execute(sourceName) { source ->
            if (offset < 0 || offset == Int.MAX_VALUE) throw SourceInvalidArgumentException()
            val catalog = registry.catalog(source.name)
            if (order != null && catalog.sortOrders.none { it.name == order }) throw SourceInvalidArgumentException()
            catalog.getList(offset, filter?.toParser(resolver(source))).map { it.toSourceContent() }
        }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent =
        execute(content.source.name) { source ->
            registry.catalog(source.name).getDetails(content.toParser(resolver(source))).toSourceContent()
                .copy(id = content.id)
        }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> =
        execute(chapter.source.name) { source ->
            registry.catalog(source.name).getPages(chapter.toParser(resolver(source))).map { it.toSourcePage() }
        }

    override suspend fun getPageUrl(page: SourcePage): String = page.url

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = execute(content.source.name) { source ->
        registry.catalog(source.name).getRecommendations(content.toParser(resolver(source))).map { it.toSourceContent() }
    }

    /** Posters with the headers the provider attached (as Android's artwork interceptor adds them). */
    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
        execute(content.source.name) { source ->
            val store = images ?: throw SourceOperationUnsupportedException()
            val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
                ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
            val persisted = CloudstreamMetadataCodec.decodeContent(content.sourceData)?.posterHeaders.orEmpty()
            val request = Request.Builder().url(url).apply {
                header("User-Agent", USER_AGENT)
                CloudstreamArtworkHeaders.resolve(source.name, url, persisted).forEach { (name, value) -> header(name, value) }
            }.build()
            val context = currentCoroutineContext()
            app.baseClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) throw IOException("Image response failed")
                val body = response.body
                store.materializeCover(sourceRef(source), content.id, body.byteStream(), body.contentLength()) {
                    context.ensureActive()
                }
            }
        }

    private suspend fun <T> execute(sourceName: String, block: suspend (CloudstreamSource) -> T): T {
        if (sourceName.isEmpty()) throw SourceInvalidArgumentException()
        return withContext(Dispatchers.IO) { block(registry.source(sourceName)) }
    }

    private fun resolver(source: CloudstreamSource): (String) -> ContentSource = { name ->
        if (name == source.name) source else throw SourceInvalidArgumentException()
    }

    companion object {
        fun sourceRef(source: CloudstreamSource) = SourceRef(source.name, source.locale, source.contentType.name)
    }
}
