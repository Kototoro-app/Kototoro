package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** IDs match Android's persisted ScrobblerService IDs. No account or list writes are performed here. */
enum class PublicTrackingSite(val id: Int, val title: String, val pageSize: Int = 20) {
    SHIKIMORI(1, "Shikimori"), ANILIST(2, "AniList"), MAL(3, "MyAnimeList"),
    KITSU(4, "Kitsu"), BANGUMI(5, "Bangumi", 10), MANGAUPDATES(6, "MangaUpdates"), SIMKL(7, "SIMKL", 10);

    fun supports(kind: String): Boolean = kind in listOf("MANGA", "NOVEL", "VIDEO") && when (this) {
        MANGAUPDATES -> kind != "VIDEO"
        SIMKL -> kind == "VIDEO"
        else -> true
    }
}

data class TrackingMetadata(
    val service: Int,
    val remoteId: Long,
    val title: String,
    val alternateTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    val score: Float? = null,
    val scoreMax: Float = 10f,
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val year: Int? = null,
    val totalChapters: Int? = null,
    val url: String,
    val mediaKind: String,
    val remoteType: String? = null,
)

/** Platform-independent discovery, parsing and media identity. The platform owns the HTTP engine. */
class PublicTrackingApi(
    private val client: HttpClient,
    clientIds: Map<Int, String> = emptyMap(),
    private val bangumiBaseUrl: String = BangumiDiscoveryApi.BASE_URL,
) {
    private val bangumi = BangumiDiscoveryApi(client)
    private val additional = AdditionalTrackingApi(client, clientIds)
    private val supplements = TrackingSupplementApi(client, clientIds, ::catalogItem, bangumiBaseUrl)

    suspend fun supplement(site: PublicTrackingSite, id: Long, kind: String): TrackingSupplement {
        require(id > 0 && site.supports(kind))
        return supplements.load(site, id, kind)
    }

    internal fun catalogItem(site: PublicTrackingSite, json: JsonObject, kind: String): TrackingMetadata = when (site) {
        PublicTrackingSite.BANGUMI -> bangumiItem(json, kind)
        PublicTrackingSite.ANILIST -> anilistItem(json, kind)
        PublicTrackingSite.KITSU -> kitsuItem(json, if (kind == "VIDEO") "anime" else "manga", kind)
        else -> additional.catalogItem(site, json, kind)
    }

    suspend fun search(
        site: PublicTrackingSite,
        query: String,
        mediaKind: String,
        page: Int = 1,
    ): List<TrackingMetadata> {
        require(page > 0)
        require(site.supports(mediaKind)) { "此网站不支持该作品类型" }
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val anime = mediaKind == "VIDEO"
        return when (site) {
            PublicTrackingSite.BANGUMI -> parse(bangumi.search(text, (page - 1) * 10, anime, bangumiBaseUrl))
                .array("data").map { bangumiItem(it.jsonObject, mediaKind, fullCover = false) }
            PublicTrackingSite.ANILIST -> {
                val format = if (mediaKind == "NOVEL") ", format: NOVEL" else ""
                val data = anilist("""query { Page(page: $page, perPage: 20) {
                    media(type: ${if (anime) "ANIME" else "MANGA"}, search: ${JsonPrimitive(text)},
                    sort: SEARCH_MATCH$format) { $ANILIST_SEARCH_FIELDS } } }""")
                data.obj("Page").array("media").map { anilistItem(it.jsonObject, mediaKind, fullCover = false) }
            }
            PublicTrackingSite.KITSU -> {
                val type = if (anime) "anime" else "manga"
                val data = client.prepareGet("https://kitsu.io/api/edge/$type") {
                    expectSuccess = true
                    url.parameters.append("filter[text]", text)
                    if (mediaKind == "NOVEL") url.parameters.append("filter[subtype]", "novel")
                    url.parameters.append("page[limit]", "20")
                    url.parameters.append("page[offset]", ((page - 1) * 20).toString())
                }.execute { parse(it.bodyAsText()) }
                data.array("data").map { kitsuItem(it.jsonObject, type, mediaKind, fullCover = false) }
            }
            else -> additional.search(site, text, mediaKind, page)
        }.filter { it.remoteId > 0 && it.title.isNotBlank() }
    }

    suspend fun details(site: PublicTrackingSite, id: Long, mediaKind: String, remoteType: String = "anime"): TrackingMetadata {
        require(id > 0)
        require(site.supports(mediaKind)) { "此网站不支持该作品类型" }
        val item = when (site) {
            PublicTrackingSite.BANGUMI -> bangumiItem(parse(bangumi.details(id, bangumiBaseUrl)), mediaKind)
            PublicTrackingSite.ANILIST -> anilistItem(anilist("query { Media(id: $id) { $ANILIST_FIELDS } }")
                .obj("Media"), mediaKind)
            PublicTrackingSite.KITSU -> {
                val type = if (mediaKind == "VIDEO") "anime" else "manga"
                val data = client.prepareGet("https://kitsu.io/api/edge/$type/$id") {
                    expectSuccess = true
                    url.parameters.append("include", "categories")
                }.execute { parse(it.bodyAsText()) }
                kitsuItem(data.obj("data"), type, mediaKind).copy(tags = data.array("included").mapNotNull {
                    it.jsonObject.takeIf { item -> item.text("type") == "categories" }?.obj("attributes")?.text("title")
                })
            }
            else -> additional.details(site, id, mediaKind, remoteType)
        }
        require(item.remoteId == id && item.title.isNotBlank()) { "追踪网站返回了无效的作品详情" }
        return item
    }

    suspend fun videos(query: String, remoteType: String, page: Int): List<TrackingMetadata> =
        additional.videos(query, remoteType, page)

    suspend fun browse(site: PublicTrackingSite, mediaKind: String, popular: Boolean, page: Int): List<TrackingMetadata> {
        require(page > 0 && site.supports(mediaKind))
        val anime = mediaKind == "VIDEO"
        return when (site) {
            PublicTrackingSite.BANGUMI -> {
                val data = client.preparePost("${bangumiBaseUrl.trimEnd('/')}/v0/search/subjects") {
                    expectSuccess = true
                    url.parameters.append("limit", "10")
                    url.parameters.append("offset", ((page - 1) * 10).toString())
                    header("Content-Type", "application/json")
                    setBody(JsonObject(mapOf("keyword" to JsonPrimitive(""),
                        "sort" to JsonPrimitive(if (popular) "heat" else "rank"),
                        "filter" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive(if (anime) 2 else 1))))),
                    )).toString())
                }.execute { parse(it.bodyAsText()) }
                data.array("data").map { bangumiItem(it.jsonObject, mediaKind, false) }
            }
            PublicTrackingSite.ANILIST -> {
                val format = if (mediaKind == "NOVEL") ", format: NOVEL" else ""
                anilist("""query { Page(page: $page, perPage: 20) {
                    media(type: ${if (anime) "ANIME" else "MANGA"},
                    sort: ${if (popular) "POPULARITY_DESC" else "SCORE_DESC"}$format) { $ANILIST_SEARCH_FIELDS } } }""")
                    .obj("Page").array("media").map { anilistItem(it.jsonObject, mediaKind, false) }
            }
            PublicTrackingSite.KITSU -> {
                val type = if (anime) "anime" else "manga"
                client.prepareGet("https://kitsu.io/api/edge/$type") {
                    expectSuccess = true
                    url.parameters.append("sort", if (popular) "popularityRank" else "ratingRank")
                    if (mediaKind == "NOVEL") url.parameters.append("filter[subtype]", "novel")
                    url.parameters.append("page[limit]", "20")
                    url.parameters.append("page[offset]", ((page - 1) * 20).toString())
                }.execute { parse(it.bodyAsText()) }.array("data")
                    .map { kitsuItem(it.jsonObject, type, mediaKind, false) }
            }
            else -> additional.browse(site, mediaKind, popular, page)
        }.filter { it.remoteId > 0 && it.title.isNotBlank() }
    }

    private suspend fun anilist(query: String): JsonObject {
        val result = client.preparePost("https://graphql.anilist.co") {
            expectSuccess = true
            header("Content-Type", "application/json")
            setBody(JsonObject(mapOf("query" to JsonPrimitive(query))).toString())
        }.execute { parse(it.bodyAsText()) }
        check(result.array("errors").isEmpty()) { "AniList 请求失败，请稍后重试" }
        return result["data"] as? JsonObject ?: error("AniList 返回了无效的数据")
    }

    private fun bangumiItem(json: JsonObject, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val id = json.long("id") ?: 0
        val original = json.text("name")
        val images = json.obj("images")
        val info = json.array("infobox").mapNotNull { entry ->
            val obj = entry.jsonObject
            if (obj.text("key") !in listOf("作者", "原作", "导演")) null else obj["value"]
        }.flatMap { value -> when (value) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.mapNotNull { (it as? JsonObject)?.text("v") }
            else -> emptyList()
        } }
        return TrackingMetadata(5, id, json.text("name_cn") ?: original.orEmpty(), listOfNotNull(original),
            (if (fullCover) images.text("large") else images.text("medium"))
                ?: images.text("common") ?: images.text("large"),
            cleanTrackingDescription(json.text("summary")), json.obj("rating").float("score"),
            tags = json.array("tags").mapNotNull { it.jsonObject.text("name") }, authors = info.distinct(),
            year = json.text("date")?.take(4)?.toIntOrNull(), totalChapters = json.int("eps")?.takeIf { it > 0 },
            url = "https://bgm.tv/subject/$id", mediaKind = if (json.int("type") == 2) "VIDEO" else kind)
    }

    private fun anilistItem(json: JsonObject, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val title = json.obj("title")
        val cover = json.obj("coverImage")
        return TrackingMetadata(2, json.long("id") ?: 0,
            title.text("userPreferred") ?: title.text("romaji") ?: title.text("native").orEmpty(),
            listOfNotNull(title.text("native"), title.text("english")),
            (if (fullCover) cover.text("extraLarge") else cover.text("medium")) ?: cover.text("large"),
            cleanTrackingDescription(json.text("description")), json.float("meanScore"), 100f,
            json.array("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            json.obj("staff").array("edges").mapNotNull { edge -> edge.jsonObject.takeIf {
                val role = it.text("role").orEmpty()
                role.contains("Story") || role.contains("Art") || role == "Original Creator" || role == "Director"
            }?.obj("node")?.obj("name")?.text("full") }.distinct(),
            json.obj("startDate").int("year"), json.int(if (kind == "VIDEO") "episodes" else "chapters"),
            json.text("siteUrl")
                ?: "https://anilist.co/${if (kind == "VIDEO") "anime" else "manga"}/${json.long("id")}",
            when {
                json.text("type") == "ANIME" -> "VIDEO"
                json.text("format") == "NOVEL" -> "NOVEL"
                else -> "MANGA"
            })
    }

    private fun kitsuItem(json: JsonObject, type: String, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val attrs = json.obj("attributes")
        val titles = attrs.obj("titles")
        val cover = attrs.obj("posterImage")
        val id = json.long("id") ?: 0
        return TrackingMetadata(4, id, attrs.text("canonicalTitle") ?: titles.text("en_jp").orEmpty(),
            titles.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            (if (fullCover) cover.text("original") else cover.text("medium"))
                ?: cover.text("large") ?: cover.text("medium"), cleanTrackingDescription(attrs.text("synopsis")),
            attrs.float("averageRating"), 100f, year = attrs.text("startDate")?.take(4)?.toIntOrNull(),
            totalChapters = attrs.int(if (type == "anime") "episodeCount" else "chapterCount"),
            url = "https://kitsu.io/$type/${attrs.text("slug") ?: id}",
            mediaKind = when {
                type == "anime" -> "VIDEO"
                attrs.text("subtype") == "novel" -> "NOVEL"
                attrs.text("subtype") != null -> "MANGA"
                else -> kind
            })
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String) = text(key)?.toLongOrNull()
    private fun JsonObject.int(key: String) = text(key)?.toIntOrNull()
    private fun JsonObject.float(key: String) = text(key)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }

    private companion object {
        const val ANILIST_SEARCH_FIELDS = """id type format title { userPreferred romaji native english }
            coverImage { medium large } siteUrl meanScore"""
        const val ANILIST_FIELDS = """id type format title { userPreferred romaji native english }
            coverImage { extraLarge large } description(asHtml: false) siteUrl meanScore genres
            startDate { year } episodes chapters staff(perPage: 25) { edges { role node { name { full } } } }"""
    }
}
