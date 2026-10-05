package org.skepsun.kototoro.source.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.MihonModelRules
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterCapabilities
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.SourceDynamicFilters
import org.skepsun.kototoro.core.source.MihonFilterRules
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourcePageRequestContext
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferenceUpdate
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/** Content executor for a platform-initialized Mihon ABI; no Android classes cross the protocol. */
class MihonSourceRuntime @JvmOverloads constructor(
    private val registry: MihonJarRegistry,
    private val imageStore: SourceImageStore? = null,
    private val preferenceContext: Any? = null,
) : SourceRuntime {
    private class Execution {
        val mutex = Mutex()
        var pendingCall: CompletableDeferred<Unit>? = null
    }
    private class Session(val execution: Execution) {
        val mangas = lru<String>(100)
        val chapters = lru<Long>(500)
        var preferences: MihonNativePreferences? = null
    }
    // The registry owns sessions with the extension generation. Native snapshots may refer back to their source.
    private val sessionOwner = Any()

    /** Manga and novels; anime sources of the same registry belong to [AniyomiSourceRuntime]. */
    override suspend fun getSources() = registry.installed().filter { it.metadata.ecosystem != SourceEcosystem.ANIYOMI }
        .flatMap { it.sources }.map { it.source }

    /** True for the names this runtime serves. */
    fun owns(sourceName: String): Boolean = registry.installed().any { extension ->
        extension.metadata.ecosystem != SourceEcosystem.ANIYOMI && extension.sources.any { it.source.name == sourceName }
    }

    override suspend fun describe(sourceName: String): SourceDescriptor = execute(sourceName) { lease, _ ->
        val nodes = MihonFilterRules.flatten(MihonNativeFilters(lease).definition.nodes)
        SourceDescriptor(
            source = lease.descriptor.source,
            sortOrders = if (lease.descriptor.supportsLatest) setOf("POPULARITY", "UPDATED") else setOf("POPULARITY"),
            defaultSortOrder = "POPULARITY",
            filterCapabilities = SourceFilterCapabilities(true, nodes.any { it.kind == SourceFilterKind.TRISTATE },
                true, true, false, false, false, false),
            pagingMode = SourcePagingMode.PAGE_INDEX,
            isDynamicFilteringSupported = true,
            isImageFetchingSupported = imageStore != null && isHttp(lease),
            // Every HTTP source has at least Kototoro's own User-Agent setting.
            isPreferencesSupported = preferenceContext != null && (MihonNativePreferences.supports(lease) || isHttp(lease)),
            isCoverFetchingSupported = imageStore != null && isHttp(lease),
            isChapterContentSupported = isNovel(lease),
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions = execute(sourceName) { lease, _ ->
        MihonFilterRules.options(MihonNativeFilters(lease).definition.nodes, lease.descriptor.source)
    }

    override suspend fun getDynamicFilters(sourceName: String): SourceDynamicFilters = execute(sourceName) { lease, _ ->
        MihonNativeFilters(lease).definition
    }

    override suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = execute(sourceName) { lease, session ->
        val context = preferenceContext ?: throw SourceOperationUnsupportedException()
        val configurable = MihonNativePreferences.supports(lease)
        if (!configurable && !isHttp(lease)) throw SourceOperationUnsupportedException()
        session.preferences = null
        val native = if (configurable) MihonNativePreferences(lease, context).also { session.preferences = it } else null
        HostUserAgent.screen(lease, context, isHttp(lease), native)
    }

    override suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ): SourcePreferenceUpdate = execute(sourceName) { lease, session ->
        val context = preferenceContext ?: throw SourceOperationUnsupportedException()
        if (nodeId == HostUserAgent.NODE_ID) {
            if (!isHttp(lease)) throw SourceOperationUnsupportedException()
            val status = HostUserAgent.update(lease, context, value)
            return@execute SourcePreferenceUpdate(status, HostUserAgent.screen(lease, context, true, session.preferences))
        }
        if (!MihonNativePreferences.supports(lease)) throw SourceOperationUnsupportedException()
        val preferences = session.preferences ?: throw SourceInvalidArgumentException()
        val result = preferences.update(revision, nodeId, value)
        SourcePreferenceUpdate(result.status, HostUserAgent.screen(lease, context, isHttp(lease), preferences))
    }

    /** PAGE_INDEX uses zero-based offsets: offset 0 invokes Mihon page 1, independent of other queries. */
    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?) =
        execute(sourceName) { lease, session ->
            if (offset < 0 || offset == Int.MAX_VALUE) throw SourceInvalidArgumentException()
            if (order != null && order != "POPULARITY" && !(order == "UPDATED" && lease.descriptor.supportsLatest)) {
                throw SourceInvalidArgumentException()
            }
            if (filter != null && filter.copy(query = null, tags = emptySet(), tagsExclude = emptySet(),
                    dynamicFilters = emptyList()) != SourceFilter()) {
                throw SourceOperationUnsupportedException()
            }
            val result = when {
                filter != null && (!filter.query.isNullOrBlank() || filter.tags.isNotEmpty() ||
                    filter.tagsExclude.isNotEmpty() || filter.dynamicFilters.isNotEmpty()) -> {
                    val filters = MihonNativeFilters(lease)
                    filters.apply(filter)
                    invoke(lease, session, "getSearchManga", offset + 1, filter.query.orEmpty(), filters.nativeList,
                        onCompleted = filters::restore)
                }
                order == "UPDATED" -> invoke(lease, session, "getLatestUpdates", offset + 1)
                else -> invoke(lease, session, "getPopularManga", offset + 1)
            } ?: throw IllegalStateException("Missing manga page")
            val adapter = MihonModelAdapter(lease)
            (MihonReflection.call(result, "getMangas") as List<*>).map { value ->
                val manga = requireNotNull(value)
                val content = adapter.content(manga)
                session.mangas[content.url] = snapshot(manga)
                content
            }
        }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) =
        execute(content.source.name) { lease, session ->
            val adapter = MihonModelAdapter(lease)
            val original = session.mangas[content.url]?.let(::snapshot) ?: adapter.manga(content)
            val update = requireNotNull(invoke(
                lease, session, "getMangaUpdate", original, content.chapters.orEmpty().map(adapter::chapter), true, true,
            ))
            val details = requireNotNull(MihonReflection.call(update, "getManga"))
            val parentUrl = MihonReflection.call(original, "getUrl") as String
            val chapters = (MihonReflection.call(update, "getChapters") as List<*>).asReversed()
                .mapIndexed { index, value ->
                    val nativeChapter = requireNotNull(value)
                    val declared = (MihonReflection.call(nativeChapter, "getChapter_number") as Number).toFloat()
                    adapter.chapter(nativeChapter, parentUrl, if (declared >= 0) declared else (index + 1).toFloat())
                        .also { session.chapters[it.id] = snapshotChapter(nativeChapter, lease) }
                }.sortedBy { it.number }
            adapter.detailFallbacks(details, original)
            session.mangas[parentUrl] = snapshot(details)
            // Both modes fetch fresh content. Snapshots retain opaque extension fields, not cached details responses.
            adapter.content(details, chapters).copy(id = content.id)
        }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) =
        execute(chapter.source.name) { lease, session ->
            val original = session.chapters[chapter.id]?.let { snapshotChapter(it, lease) }
                ?: MihonModelAdapter(lease).chapter(chapter)
            val result = invoke(lease, session, "getPageList", original) as List<*>
            val http = isHttp(lease)
            result.map { value ->
                val page = requireNotNull(value)
                val index = MihonReflection.call(page, "getIndex") as Int
                val pageUrl = MihonReflection.call(page, "getUrl") as String
                val imageUrl = MihonModelAdapter.read(page, "getImageUrl") as? String
                val url = when {
                    http && imageUrl.isNullOrBlank() && pageUrl.isNotBlank() ->
                        "mihon://resolve?page_url=${encode(pageUrl)}&index=$index"
                    http && !imageUrl.isNullOrBlank() && pageUrl.isNotBlank() && pageUrl != imageUrl ->
                        "mihon://image?page_url=${encode(pageUrl)}&image_url=${encode(imageUrl)}&index=$index"
                    else -> imageUrl ?: pageUrl
                }
                SourcePage(
                    id = MihonModelRules.pageId(chapter.id.toString(), index), url = url, preview = null,
                    source = lease.descriptor.source, headers = if (http && !imageUrl.isNullOrBlank()) {
                        pageHeaders(lease.instance, page)
                    } else emptyMap(),
                    requestContext = SourcePageRequestContext(index, pageUrl, imageUrl,
                        MihonModelAdapter.read(page, "getUri")?.toString()),
                )
            }
        }

    /**
     * A Tsundoku novel chapter. Text comes from the page itself when the extension's ABI keeps it there
     * (`Page.text`), otherwise from the source's own `fetchPageText`. Extensions answer with HTML paragraphs (NovelFull
     * does) or plain text; markup passes through for the reader to sanitise, plain text is escaped into a paragraph.
     * Image pages become the chapter's images. Failures surface unless at least one page produced text.
     */
    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent? =
        execute(chapter.source.name) { lease, session ->
            if (!isNovel(lease)) throw SourceOperationUnsupportedException()
            val original = session.chapters[chapter.id]?.let { snapshotChapter(it, lease) }
                ?: MihonModelAdapter(lease).chapter(chapter)
            val pages = invoke(lease, session, "getPageList", original) as? List<*> ?: return@execute null
            val parts = mutableListOf<String>()
            val images = mutableListOf<SourceContentImage>()
            var failure: Exception? = null
            for (value in pages) {
                val page = requireNotNull(value)
                (MihonModelAdapter.read(page, "getImageUrl") as? String)?.takeIf(String::isNotBlank)?.let { imageUrl ->
                    images += SourceContentImage(imageUrl, if (isHttp(lease)) pageHeaders(lease.instance, page) else emptyMap())
                }
                val inline = MihonModelAdapter.read(page, "getText")?.toString()?.takeIf(String::isNotBlank)
                if (inline != null) { parts += inline; continue }
                try {
                    (invoke(lease, session, "fetchPageText", page) as? String)?.takeIf(String::isNotBlank)?.let(parts::add)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { failure = failure ?: error }
            }
            if (parts.isEmpty()) {
                failure?.let { throw it }
                null
            } else SourceChapterContent(parts.joinToString("\n") { if (HTML_MARKUP.containsMatchIn(it)) it else "<p>${escapeHtml(it)}</p>" }, images)
        }

    override suspend fun getPageUrl(page: SourcePage): String = execute(page.source.name) { lease, session ->
        val native = page.requestContext ?: legacyPageContext(page) ?: return@execute page.url
        native.imageUrl?.takeIf(String::isNotBlank)?.let { return@execute it }
        if (!isHttp(lease)) native.url else {
            val nativePage = MihonReflection.create(lease.loader, "Page", native.index, native.url, null, null)
            invoke(lease, session, "getImageUrl", nativePage) as String
        }
    }

    override suspend fun fetchImage(page: SourcePage): SourceImageArtifact = execute(page.source.name) { lease, session ->
        val store = imageStore ?: throw SourceOperationUnsupportedException()
        if (!isHttp(lease)) throw SourceOperationUnsupportedException()
        val original = page.requestContext ?: legacyPageContext(page) ?: throw SourceInvalidArgumentException()
        if (original.uri != null) throw SourceOperationUnsupportedException()
        val resolved = original.imageUrl?.takeIf(String::isNotBlank) ?: run {
            val unresolved = MihonReflection.create(lease.loader, "Page", original.index, original.url, null, null)
            invoke(lease, session, "getImageUrl", unresolved) as String
        }
        if (resolved.isBlank()) throw SourceInvalidArgumentException()
        val native = MihonReflection.create(lease.loader, "Page", original.index, original.url, resolved, null)
        val response = invoke(lease, session, "getImage", native,
            onResult = { MihonImageResponse(requireNotNull(it), lease) },
            onDiscarded = { (it as? MihonImageResponse)?.close() }) as MihonImageResponse
        response.use { it.materialize(store, page.id) }
    }

    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
        execute(content.source.name) { lease, session ->
            val store = imageStore ?: throw SourceOperationUnsupportedException()
            if (!isHttp(lease)) throw SourceOperationUnsupportedException()
            val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
                ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
            val request = coverRequest(lease, url)
            val finished = CompletableDeferred<Unit>()
            session.execution.pendingCall = finished
            val response = MihonHttpCall.await(lease, request) { finished.complete(Unit) }
            response.use { it.materializeCover(store, content.id) }
        }

    private fun coverRequest(lease: MihonJarRegistry.SourceLease, url: String): Any {
        try {
            val page = MihonReflection.create(lease.loader, "Page", 0, "", url, null)
            val request = requireNotNull(MihonReflection.call(lease.instance, "imageRequest", page))
            val referer = MihonModelRules.coverReferer(url, MihonReflection.call(request, "header", "Referer") as? String)
            if (referer == null) return request
            val builder = requireNotNull(MihonReflection.call(request, "newBuilder"))
            MihonReflection.call(builder, "header", "Referer", referer)
            return requireNotNull(MihonReflection.call(builder, "build"))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            // Some extensions require chapter-only context. Fall back to their generic headers and same client.
            val builder = Class.forName("okhttp3.Request\$Builder", true, lease.loader).getConstructor().newInstance()
            MihonReflection.call(builder, "url", url)
            MihonReflection.call(builder, "headers", requireNotNull(MihonReflection.call(lease.instance, "getHeaders")))
            MihonReflection.call(builder, "get")
            return requireNotNull(MihonReflection.call(builder, "build"))
        }
    }

    private fun legacyPageContext(page: SourcePage): SourcePageRequestContext? {
        if (!page.url.startsWith("mihon://")) return null
        val uri = try { URI(page.url) } catch (_: URISyntaxException) { throw SourceInvalidArgumentException() }
        val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { entry ->
            decode(entry.substringBefore('=')) to decode(entry.substringAfter('=', ""))
        }
        if (uri.host != "image" && uri.host != "resolve") throw SourceInvalidArgumentException()
        val original = parameters["page_url"]?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
        val index = parameters["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: throw SourceInvalidArgumentException()
        val image = if (uri.host == "image") parameters["image_url"]?.takeIf(String::isNotBlank)
            ?: throw SourceInvalidArgumentException() else null
        return SourcePageRequestContext(index, original, image)
    }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = execute(content.source.name) { _, _ ->
        // Suwayomi's pinned baseline has no related API; a shared search fallback is not wired yet.
        throw SourceOperationUnsupportedException()
    }

    private suspend fun <T> execute(sourceName: String, block: suspend (MihonJarRegistry.SourceLease, Session) -> T): T =
        withContext(Dispatchers.IO) {
            registry.withSourceSuspending(sourceName) { lease ->
                val execution = lease.state(executionOwner, ::Execution)
                val session = lease.state(sessionOwner) { Session(execution) }
                execution.mutex.withLock {
                    execution.pendingCall?.await()
                    currentCoroutineContext().ensureActive()
                    // Kototoro's per-source User-Agent; a source it cannot apply to keeps its own headers.
                    preferenceContext?.let { context -> if (isHttp(lease)) runCatching { HostUserAgent.apply(lease, context) } }
                    try {
                        block(lease, session).also { currentCoroutineContext().ensureActive() }
                    } catch (error: LinkageError) {
                        throw IllegalStateException("Mihon runtime ABI failure", error)
                    }
                }
            }
        }

    private suspend fun invoke(
        lease: MihonJarRegistry.SourceLease, session: Session, name: String, vararg arguments: Any?,
        onCompleted: () -> Unit = {},
        onResult: (Any?) -> Any? = { it },
        onDiscarded: (Any?) -> Unit = {},
    ): Any? {
        val finished = CompletableDeferred<Unit>()
        session.execution.pendingCall = finished
        return MihonReflection.callSuspending(lease, name, *arguments, onCompleted = {
            try { onCompleted() } finally { finished.complete(Unit) }
        }, onResult = onResult, onDiscarded = onDiscarded)
    }

    private fun snapshot(manga: Any): Any = (MihonModelAdapter.read(manga, "copy") ?: manga).also {
        // The pinned Suwayomi copy() omits memo; extension context cannot rely on that default implementation.
        copyFields(it, manga, listOf("Memo", "Genres", "AltTitles", "Banner", "ContentRating", "Score", "ReadingMode"))
    }

    private fun snapshotChapter(chapter: Any, lease: MihonJarRegistry.SourceLease): Any =
        MihonReflection.create(lease.loader, "SChapterImpl").also {
            MihonReflection.call(it, "copyFrom", chapter)
            copyFields(it, chapter, listOf("Memo", "Number", "Volume", "Scanlators", "Note", "Locked", "Read", "Last_page_read"))
        }

    private fun copyFields(target: Any, original: Any, fields: List<String>) {
        for (field in fields) {
            val value = MihonModelAdapter.read(original, "get$field") ?: continue
            MihonReflection.optional(target, "set$field", value)
        }
    }

    private fun isNovel(lease: MihonJarRegistry.SourceLease) = lease.descriptor.source.contentType.endsWith("NOVEL")

    private fun escapeHtml(value: String) = buildString(value.length + 16) {
        for (char in value) when (char) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(char)
        }
    }

    private fun isHttp(lease: MihonJarRegistry.SourceLease): Boolean = try {
        Class.forName("eu.kanade.tachiyomi.source.online.HttpSource", false, lease.loader).isInstance(lease.instance)
    } catch (_: ClassNotFoundException) { false }

    private fun pageHeaders(source: Any, page: Any): Map<String, String> = try {
        val request = requireNotNull(MihonReflection.call(source, "imageRequest", page))
        val headers = requireNotNull(MihonReflection.call(request, "headers"))
        val size = MihonReflection.call(headers, "size") as Int
        buildMap {
            for (index in 0 until size) {
                put(MihonReflection.call(headers, "name", index) as String,
                    MihonReflection.call(headers, "value", index) as String)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // As on Android, optional header projection cannot make the chapter's entire page list fail.
        emptyMap()
    }

    companion object {
        /** Block or inline tags that mark a chapter text as HTML; a lone `<two>` in prose is not one. */
        private val HTML_MARKUP = Regex("<\\s*/?\\s*(p|br|div|span|img|h[1-6]|strong|em|b|i|u|ul|ol|li|blockquote|a|hr|section|article|pre|table|tr|td)\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val executionOwner = Any()
        private fun <K> lru(maximum: Int) = object : LinkedHashMap<K, Any>(maximum, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Any>) = size > maximum
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") }
            catch (_: IllegalArgumentException) { throw SourceInvalidArgumentException() }
    }
}
