package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.ProxyBuilder
import io.ktor.http.Url
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.plugins.defaultRequest
import kotlinx.coroutines.CancellationException
import kotlin.native.Platform
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.library.SourceTracking
import org.skepsun.kototoro.core.library.encodeTrackingStrings
import org.skepsun.kototoro.core.library.decodeTrackingStrings
import org.skepsun.kototoro.tracking.discovery.PublicTrackingApi
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite
import org.skepsun.kototoro.tracking.discovery.TrackingMetadata
import org.skepsun.kototoro.tracking.discovery.TrackingPerson
import org.skepsun.kototoro.tracking.discovery.TrackingRelated
import org.skepsun.kototoro.tracking.discovery.TrackingSupplement
import org.skepsun.kototoro.tracking.discovery.TrackingScheduleDay

class IosTrackingSite internal constructor(private val value: PublicTrackingSite) {
    val id: Int get() = value.id
    val title: String get() = value.title
    val pageSize: Int get() = value.pageSize
    val supportsNotes: Boolean get() = id !in listOf(6, 7)
    fun supports(mediaKind: String): Boolean = value.supports(mediaKind)
}

data class IosTrackingSection(val id: String, val title: String)

class IosTrackingItem internal constructor(
    internal val value: TrackingMetadata,
    internal val detailed: Boolean = false,
) {
    val key: String get() = "${value.service}:${value.remoteId}"
    val service: Int get() = value.service
    val remoteId: String get() = value.remoteId.toString()
    val siteTitle: String get() = trackingSiteTitle(service)
    val title: String get() = value.title
    val alternateTitles: List<String> get() = value.alternateTitles.filter { it != title }.distinct()
    val coverUrl: String? get() = value.coverUrl
    val summary: String? get() = value.description
    val authors: List<String> get() = value.authors
    val tags: List<String> get() = value.tags
    val year: Int? get() = value.year
    val score: Float? get() = value.score
    val scoreMax: Float get() = value.scoreMax
    val url: String get() = value.url
    val mediaKind: String get() = value.mediaKind
    val totalChapters: Int? get() = value.totalChapters
    val remoteType: String? get() = value.remoteType
}

class IosTrackingPerson internal constructor(value: TrackingPerson) {
    val id = value.id
    val name = value.name
    val image = value.image
    val url = value.url
    val role = value.role
    val voiceActors = value.voiceActors.map(::IosTrackingPerson)
}

class IosTrackingRelated internal constructor(value: TrackingRelated) {
    val item = IosTrackingItem(value.item)
    val relation = value.relation
}

class IosTrackingSupplement internal constructor(value: TrackingSupplement) {
    val characters = value.characters.map(::IosTrackingPerson)
    val staff = value.staff.map(::IosTrackingPerson)
    val related = value.related.map(::IosTrackingRelated)
    val recommendations = value.recommendations.map(::IosTrackingRelated)
}

internal fun trackingSiteTitle(service: Int): String =
    PublicTrackingSite.entries.firstOrNull { it.id == service }?.title
        ?: when (service) {
            1 -> "Shikimori"
            3 -> "MyAnimeList"
            6 -> "MangaUpdates"
            7 -> "SIMKL"
            else -> localized("追踪网站")
        }

/** Native composition root; HTTP/parsing and binding/display rules live in the shared modules. */
class IosTracking internal constructor(
    private val library: IosLibrary,
    private val fetchDetails: suspend (PublicTrackingSite, Long, String) -> TrackingMetadata = { site, id, kind ->
        api.details(site, id, kind)
    },
) {
    private val store = SourceTracking(library.database)
    val sites: List<IosTrackingSite> = listOf(PublicTrackingSite.BANGUMI, PublicTrackingSite.ANILIST,
        PublicTrackingSite.MAL, PublicTrackingSite.KITSU, PublicTrackingSite.SHIKIMORI,
        PublicTrackingSite.MANGAUPDATES, PublicTrackingSite.SIMKL).map(::IosTrackingSite)
    val accounts by lazy { IosTrackingAccounts(library, client) }

    fun recommendationSections(service: Int, mediaKind: String): List<IosTrackingSection> {
        if (!site(service).supports(mediaKind)) return emptyList()
        return buildList {
            add(IosTrackingSection("popular", localized("热门作品")))
            if (service != 7) add(IosTrackingSection("rating", localized("高分作品")))
            if (site(service).supportsCalendar(mediaKind)) add(IosTrackingSection("calendar", localized("每日放送")))
            if (service == 7 && mediaKind == "VIDEO") add(IosTrackingSection("calendar_tv", localized("每日播出 · 剧集")))
        }
    }

    @Throws(Exception::class)
    suspend fun recommendations(service: Int, mediaKind: String, category: String, weekday: Int,
        page: Int, dayStart: Long, dayEnd: Long): List<IosTrackingItem> = request {
        api.recommendations(site(service), mediaKind, category, weekday, page,
            TrackingScheduleDay(dayStart, dayEnd)).map(::IosTrackingItem)
    }

    @Throws(Exception::class)
    suspend fun browse(service: Int, mediaKind: String, popular: Boolean, page: Int): List<IosTrackingItem> = request {
        api.browse(site(service), mediaKind, popular, page).map(::IosTrackingItem)
    }

    @Throws(Exception::class)
    suspend fun search(service: Int, query: String, mediaKind: String, page: Int): List<IosTrackingItem> = request {
        api.search(site(service), query, mediaKind, page).map(::IosTrackingItem)
    }

    @Throws(Exception::class)
    suspend fun details(item: IosTrackingItem, mediaKind: String): IosTrackingItem = request {
        IosTrackingItem(resolveDetails(item, mediaKind), detailed = true)
    }

    @Throws(Exception::class)
    suspend fun videos(query: String, remoteType: String, page: Int): List<IosTrackingItem> = request {
        api.videos(query, remoteType, page).map(::IosTrackingItem)
    }

    @Throws(Exception::class)
    suspend fun supplement(item: IosTrackingItem): IosTrackingSupplement = request {
        IosTrackingSupplement(api.supplement(site(item.service), item.value.remoteId, item.mediaKind))
    }

    @Throws(Exception::class)
    suspend fun bindings(content: IosSourceContent): List<IosTrackingItem> =
        store.bindings(content.value.id).map { IosTrackingItem(it.toMetadata(content.mediaKind)) }

    @Throws(Exception::class)
    suspend fun selected(content: IosSourceContent): IosTrackingItem? =
        store.selected(content.value.id)?.let { IosTrackingItem(it.toMetadata(content.mediaKind)) }

    @Throws(Exception::class)
    suspend fun decorate(content: IosSourceContent): IosSourceContent =
        IosSourceContent(content.value, store.selected(content.value.id))

    @Throws(Exception::class)
    suspend fun bind(content: IosSourceContent, item: IosTrackingItem) {
        // Search results must resolve before changing display authority; previews already hold complete details.
        val details = if (item.detailed) item.value else {
            request { resolveDetails(item, content.mediaKind) }
        }
        require(details.mediaKind == content.mediaKind) { localized("作品类型不匹配，请选择相同类型的作品资料") }
        library.saveDetails(content)
        store.bind(content.value, details.toEntity())
    }

    @Throws(Exception::class)
    suspend fun select(content: IosSourceContent, item: IosTrackingItem?) =
        store.select(content.value, item?.service, item?.value?.remoteId)

    @Throws(Exception::class)
    suspend fun unbind(content: IosSourceContent, service: Int) {
        store.unbind(content.value, service)
        accounts.stopTracking(content, service)
    }

    internal suspend fun selected(ids: List<Long>): Map<Long, TrackingSiteItemEntity> = store.selected(ids)

    private fun site(id: Int) = PublicTrackingSite.entries.firstOrNull { it.id == id }
        ?: error(localized("此追踪网站尚不支持在线检索"))

    private suspend fun resolveDetails(item: IosTrackingItem, kind: String): TrackingMetadata =
        if (item.service == 7 && item.remoteType != null) api.details(site(7), item.value.remoteId, kind, item.remoteType!!)
        else fetchDetails(site(item.service), item.value.remoteId, kind)

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HttpRequestTimeoutException) {
        throw IllegalStateException(localized("连接超时，请重试或切换网站"), error)
    } catch (error: ResponseException) {
        throw IllegalStateException(localized("网站暂时无法响应（HTTP %@），请稍后重试", error.response.status.value), error)
    } catch (error: Exception) {
        throw IllegalStateException(localized("暂时无法读取作品资料，请检查网络或切换网站"), error)
    }

    internal companion object {
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        val client by lazy {
            HttpClient(Darwin) {
                // Test-only loopback routing; release builds always use the device's network configuration.
                if (Platform.isDebugBinary) {
                    val address = NSProcessInfo.processInfo.environment["KOTOTORO_TRACKING_TEST_PROXY"] as? String
                    if (!address.isNullOrBlank()) {
                        val proxyUrl = Url(address)
                        require(proxyUrl.host in listOf("127.0.0.1", "localhost", "::1"))
                        engine {
                            proxy = ProxyBuilder.http(proxyUrl)
                            // Ktor 3.5 only populates the HTTP keys; HTTPS requests also need CONNECT routing.
                            configureSession {
                                connectionProxyDictionary = connectionProxyDictionary.orEmpty() + mapOf<Any?, Any?>(
                                    "HTTPSEnable" to 1,
                                    "HTTPSProxy" to proxyUrl.host,
                                    "HTTPSPort" to proxyUrl.port,
                                )
                            }
                        }
                    }
                }
                expectSuccess = true
                install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 30_000 }
                defaultRequest { header("User-Agent", "Kototoro-iOS/1.0") }
            }
        }
        val api: PublicTrackingApi get() = PublicTrackingApi(client, trackingClients.mapValues { it.value.clientId },
            NSUserDefaults.standardUserDefaults.stringForKey("tracking.bangumi.apiBase") ?: "https://api.bgm.tv")
    }
}

internal fun TrackingMetadata.toEntity(): TrackingSiteItemEntity {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    return TrackingSiteItemEntity(
        service = service,
        remoteId = remoteId,
        title = title,
        altTitles = encodeTrackingStrings(alternateTitles),
        primaryTitle = null,
        secondaryTitle = null,
        rating = score,
        scoreMax = scoreMax,
        rank = null,
        summary = description,
        progressText = null,
        updatedAtText = null,
        tags = encodeTrackingStrings(tags),
        year = year,
        authors = encodeTrackingStrings(authors),
        coverUrl = coverUrl,
        totalEpisodes = totalChapters,
        publishDate = null,
        siteUrl = url,
        cachedAt = now,
        updatedAt = now,
    )
}

private fun TrackingSiteItemEntity.toMetadata(kind: String) = TrackingMetadata(service, remoteId, title,
    decodeTrackingStrings(altTitles), coverUrl, summary, rating, scoreMax ?: 10f, decodeTrackingStrings(tags),
    decodeTrackingStrings(authors), year, totalEpisodes, siteUrl.orEmpty(), kind,
    if (service == 7) when { siteUrl?.contains("/movies/") == true -> "movies"; siteUrl?.contains("/tv/") == true -> "tv"; else -> "anime" } else null)
