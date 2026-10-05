package org.skepsun.kototoro.source.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.MihonFilterRules
import org.skepsun.kototoro.core.source.MihonModelRules
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceDynamicFilters
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterCapabilities
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdate
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRuntime
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Anime sources of the Aniyomi ABI. Lists, details and episodes are the manga flow under other names; an episode's
 * "pages" are its playable streams: each carries the headers, subtitle and audio tracks the extension attached.
 */
class AniyomiSourceRuntime @JvmOverloads constructor(
    private val registry: MihonJarRegistry,
    private val imageStore: SourceImageStore? = null,
    /** The compatibility runtime's Context; settings screens need it, as for Mihon sources. */
    private val preferenceContext: Any? = null,
) : SourceRuntime {
    private class Execution {
        val mutex = Mutex()
        var pendingCall: CompletableDeferred<Unit>? = null
    }
    private class Session(val execution: Execution) {
        val animes = lru<String>(100)
        val episodes = lru<Long>(500)
        var preferences: MihonNativePreferences? = null
    }
    private val sessionOwner = Any()
    private val abi = NativeAbi.ANIYOMI

    /** True for the names this runtime serves, so a router can keep the ecosystems apart. */
    fun owns(sourceName: String): Boolean = registry.installed().any { extension ->
        extension.metadata.ecosystem == SourceEcosystem.ANIYOMI && extension.sources.any { it.source.name == sourceName }
    }

    override suspend fun getSources() = registry.installed().filter { it.metadata.ecosystem == SourceEcosystem.ANIYOMI }
        .flatMap { it.sources }.map { it.source }

    override suspend fun describe(sourceName: String): SourceDescriptor = execute(sourceName) { lease, _ ->
        val nodes = filters(lease)?.let { MihonFilterRules.flatten(it.definition.nodes) }.orEmpty()
        SourceDescriptor(
            source = lease.descriptor.source,
            sortOrders = if (lease.descriptor.supportsLatest) setOf("POPULARITY", "UPDATED") else setOf("POPULARITY"),
            defaultSortOrder = "POPULARITY",
            filterCapabilities = SourceFilterCapabilities(true, nodes.any { it.kind == SourceFilterKind.TRISTATE },
                true, true, false, false, false, false),
            pagingMode = SourcePagingMode.PAGE_INDEX,
            isDynamicFilteringSupported = true,
            isImageFetchingSupported = false,
            // Every HTTP source has at least Kototoro's own User-Agent setting.
            isPreferencesSupported = preferenceContext != null && (MihonNativePreferences.supports(lease, abi) || isHttp(lease)),
            isCoverFetchingSupported = imageStore != null && isHttp(lease),
            isChapterContentSupported = false,
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions = execute(sourceName) { lease, _ ->
        filters(lease)?.let { MihonFilterRules.options(it.definition.nodes, lease.descriptor.source) }
            ?: SourceFilterOptions(emptySet(), emptyList(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
    }

    override suspend fun getDynamicFilters(sourceName: String): SourceDynamicFilters = execute(sourceName) { lease, _ ->
        filters(lease)?.definition ?: SourceDynamicFilters(lease.descriptor.source, emptyList())
    }

    /** `ConfigurableAnimeSource.setupPreferenceScreen` through the same native-control bridge as Mihon sources. */
    override suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = execute(sourceName) { lease, session ->
        val context = preferenceContext ?: throw SourceOperationUnsupportedException()
        val configurable = MihonNativePreferences.supports(lease, abi)
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
        if (!MihonNativePreferences.supports(lease, abi)) throw SourceOperationUnsupportedException()
        val preferences = session.preferences ?: throw SourceInvalidArgumentException()
        val result = preferences.update(revision, nodeId, value)
        SourcePreferenceUpdate(result.status, HostUserAgent.screen(lease, context, isHttp(lease), preferences))
    }

    /** PAGE_INDEX: offset 0 asks the extension for page 1, as for Mihon. */
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
                    val native = MihonNativeFilters(lease, abi)
                    native.apply(filter)
                    invoke(lease, session, "getSearchAnime", offset + 1, filter.query.orEmpty(), native.nativeList,
                        onCompleted = native::restore)
                }
                order == "UPDATED" -> invoke(lease, session, "getLatestUpdates", offset + 1)
                else -> invoke(lease, session, "getPopularAnime", offset + 1)
            } ?: throw IllegalStateException("Missing anime page")
            val adapter = AniyomiModelAdapter(lease)
            (MihonReflection.call(result, "getAnimes") as List<*>).map { value ->
                val anime = requireNotNull(value)
                val content = adapter.content(anime)
                session.animes[content.url] = adapter.copy(anime)
                content
            }
        }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) =
        execute(content.source.name) { lease, session ->
            val adapter = AniyomiModelAdapter(lease)
            val original = session.animes[content.url]?.let(adapter::copy) ?: adapter.anime(content)
            val details = requireNotNull(retrying { invoke(lease, session, "getAnimeDetails", adapter.copy(original)) })
            val parentUrl = AniyomiModelAdapter.read(original, "getUrl") as String
            val episodes = (retrying { invoke(lease, session, "getEpisodeList", adapter.copy(original)) } as? List<*>).orEmpty()
            // Sources list newest first; numbers missing from the extension follow the oldest-first order.
            val chapters = episodes.asReversed().mapIndexed { index, value ->
                val native = requireNotNull(value)
                val declared = (AniyomiModelAdapter.read(native, "getEpisode_number") as? Number)?.toFloat() ?: -1f
                adapter.episode(native, parentUrl, if (declared > 0) declared else (index + 1).toFloat())
                    .also { session.episodes[it.id] = adapter.copyEpisode(native) }
            }.sortedBy { it.number }
            adapter.detailFallbacks(details, original)
            session.animes[parentUrl] = adapter.copy(details)
            adapter.content(details, chapters).copy(id = content.id)
        }

    /** The episode's streams, as Aniyomi's own loader finds them: the plain video list, else the hosters'. */
    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> =
        execute(chapter.source.name) { lease, session ->
            val adapter = AniyomiModelAdapter(lease)
            val episode = session.episodes[chapter.id]?.let(adapter::copyEpisode) ?: adapter.episode(chapter)
            val videos = retrying { compatibleVideos(lease, session, episode) }
            // The extension's own preference decides which stream is offered first; the sort is stable.
            videos.mapIndexedNotNull { index, video -> adapter.page(video, chapter, index)?.let { video to it } }
                .sortedBy { (video, _) -> if (adapter.preferred(video)) 0 else 1 }
                .map { (_, page) -> page }
        }

    private suspend fun compatibleVideos(lease: MihonJarRegistry.SourceLease, session: Session, episode: Any): List<Any> {
        var legacyFailure: Throwable? = null
        val legacy = try {
            invoke(lease, session, "getVideoList", episode) as? List<*>
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            legacyFailure = error
            null
        }.orEmpty().filterNotNull()
        if (legacy.isNotEmpty()) return resolve(lease, session, legacy)

        val hosters = try {
            invoke(lease, session, "getHosterList", episode) as? List<*>
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }.orEmpty().filterNotNull()
        if (hosters.isNotEmpty()) {
            val videos = hosters.flatMap { hoster ->
                val inline = AniyomiModelAdapter.read(hoster, "getVideoList") as? List<*>
                (inline ?: invoke(lease, session, "getVideoList", hoster) as? List<*>).orEmpty().filterNotNull()
            }
            return resolve(lease, session, videos)
        }
        legacyFailure?.let { throw it }
        return emptyList()
    }

    /** Videos that are not final yet go through the extension's resolver, which may drop them. */
    private suspend fun resolve(lease: MihonJarRegistry.SourceLease, session: Session, videos: List<Any>): List<Any> {
        if (!isHttp(lease)) return videos
        return videos.mapNotNull { video ->
            if (AniyomiModelAdapter.read(video, "getInitialized") as? Boolean == true) video
            else invoke(lease, session, "resolveVideo", video)
        }
    }

    /** A video page's URL is the stream itself. */
    override suspend fun getPageUrl(page: SourcePage): String = page.url

    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
        execute(content.source.name) { lease, session ->
            val store = imageStore ?: throw SourceOperationUnsupportedException()
            if (!isHttp(lease)) throw SourceOperationUnsupportedException()
            val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
                ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
            val finished = CompletableDeferred<Unit>()
            session.execution.pendingCall = finished
            val response = MihonHttpCall.await(lease, coverRequest(lease, url)) { finished.complete(Unit) }
            response.use { it.materializeCover(store, content.id) }
        }

    /** The source's own headers on a GET of the cover, with the Referer rule the other ecosystems use. */
    private fun coverRequest(lease: MihonJarRegistry.SourceLease, url: String): Any {
        val builder = Class.forName("okhttp3.Request\$Builder", true, lease.loader).getConstructor().newInstance()
        MihonReflection.call(builder, "url", url)
        val headers = requireNotNull(MihonReflection.call(lease.instance, "getHeaders"))
        MihonReflection.call(builder, "headers", headers)
        MihonModelRules.coverReferer(url, MihonReflection.call(headers, "get", "Referer") as? String)
            ?.let { MihonReflection.call(builder, "header", "Referer", it) }
        MihonReflection.call(builder, "get")
        return requireNotNull(MihonReflection.call(builder, "build"))
    }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = throw SourceOperationUnsupportedException()

    private fun filters(lease: MihonJarRegistry.SourceLease): MihonNativeFilters? = try {
        MihonNativeFilters(lease, abi)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Sources without a usable filter list still list and search.
        null
    }

    /** One retry after a short pause for network failures, as the Android host does. */
    private suspend fun <T> retrying(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is IOException || error.cause is IOException) {
            delay(500)
            block()
        } else throw error
    }

    private fun isHttp(lease: MihonJarRegistry.SourceLease): Boolean = try {
        Class.forName("eu.kanade.tachiyomi.animesource.online.AnimeHttpSource", false, lease.loader).isInstance(lease.instance)
    } catch (_: ClassNotFoundException) { false }

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
                        throw IllegalStateException("Aniyomi runtime ABI failure", error)
                    }
                }
            }
        }

    private suspend fun invoke(
        lease: MihonJarRegistry.SourceLease, session: Session, name: String, vararg arguments: Any?,
        onCompleted: () -> Unit = {},
    ): Any? {
        val finished = CompletableDeferred<Unit>()
        session.execution.pendingCall = finished
        return MihonReflection.callSuspending(lease, name, *arguments, onCompleted = {
            try { onCompleted() } finally { finished.complete(Unit) }
        })
    }

    private companion object {
        val executionOwner = Any()
        fun <K> lru(maximum: Int) = object : LinkedHashMap<K, Any>(maximum, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Any>) = size > maximum
        }
    }
}
