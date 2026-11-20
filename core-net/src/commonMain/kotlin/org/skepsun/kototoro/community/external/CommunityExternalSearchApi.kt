package org.skepsun.kototoro.community.external

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Which tracking site a hit came from; names match the community key services in core-domain. */
enum class CommunityExternalSite {
    ANILIST,
    BANGUMI,
}

/** The kind of list a search asks: AniList's MANGA covers novels too, Bangumi's books cover both. */
enum class CommunityExternalSearchKind {
    BOOK,
    VIDEO,
}

/**
 * One search result. [format] is the site's own word for what it is (`MANGA`, `NOVEL`, `TV` on AniList;
 * `<type>/<platform>` such as `1/漫画` on Bangumi), and [titles] every name the site lists for it.
 */
data class CommunityExternalHit(
    val site: CommunityExternalSite,
    val id: Long,
    val format: String?,
    val titles: List<String>,
    val isAdult: Boolean,
)

/** A search the site refused or failed. Nothing about the work is known from it, so it must not be cached as "no match". */
class CommunityExternalSearchException(
    val site: CommunityExternalSite,
    val status: Int,
    val retryAfterSeconds: Long?,
) : Exception("${site.name} search failed with HTTP $status")

/**
 * Title search on the public, unauthenticated APIs of AniList and Bangumi, used to find a work's id on a site the reader
 * never bound it to (community plan §3.5). The platform owns the client and its lifetime; Bangumi asks for an identifying
 * user agent, which is set per request so the client's own stays untouched.
 */
class CommunityExternalSearchApi(
    private val httpClient: HttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    suspend fun search(site: CommunityExternalSite, query: String, kind: CommunityExternalSearchKind): List<CommunityExternalHit> {
        if (query.isBlank()) return emptyList()
        currentCoroutineContext().ensureActive()
        return when (site) {
            CommunityExternalSite.ANILIST -> searchAniList(query, kind)
            CommunityExternalSite.BANGUMI -> searchBangumi(query, kind)
        }
    }

    private suspend fun searchAniList(query: String, kind: CommunityExternalSearchKind): List<CommunityExternalHit> {
        val body = buildJsonObject {
            put("query", ANILIST_QUERY)
            putJsonObject("variables") {
                put("search", query)
                put("type", if (kind == CommunityExternalSearchKind.VIDEO) "ANIME" else "MANGA")
            }
        }
        return post(CommunityExternalSite.ANILIST, ANILIST_URL, body) { root ->
            root.obj("data")?.obj("Page")?.array("media").orEmpty().mapNotNull { element ->
                val media = element as? JsonObject ?: return@mapNotNull null
                val id = media.long("id") ?: return@mapNotNull null
                val title = media.obj("title")
                CommunityExternalHit(
                    site = CommunityExternalSite.ANILIST,
                    id = id,
                    format = media.text("format"),
                    titles = (listOf("romaji", "english", "native").mapNotNull { title?.text(it) } +
                        media.array("synonyms").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                        .filter { it.isNotBlank() }.distinct(),
                    isAdult = media["isAdult"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        }
    }

    private suspend fun searchBangumi(query: String, kind: CommunityExternalSearchKind): List<CommunityExternalHit> {
        val body = buildJsonObject {
            put("keyword", query)
            putJsonObject("filter") {
                putJsonArray("type") {
                    if (kind == CommunityExternalSearchKind.VIDEO) {
                        add(BANGUMI_ANIME)
                        add(BANGUMI_REAL)
                    } else {
                        add(BANGUMI_BOOK)
                    }
                }
            }
        }
        return post(CommunityExternalSite.BANGUMI, "$BANGUMI_URL?limit=$PAGE_SIZE", body) { root ->
            root.array("data").orEmpty().mapNotNull { element ->
                val subject = element as? JsonObject ?: return@mapNotNull null
                val id = subject.long("id") ?: return@mapNotNull null
                val aliases = subject.array("infobox").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .filter { it.text("key") == "别名" }
                    .flatMap { item -> bangumiValues(item["value"]) }
                CommunityExternalHit(
                    site = CommunityExternalSite.BANGUMI,
                    id = id,
                    format = "${subject.long("type") ?: 0}/${subject.text("platform").orEmpty()}",
                    titles = (listOfNotNull(subject.text("name"), subject.text("name_cn")) + aliases)
                        .filter { it.isNotBlank() }.distinct(),
                    isAdult = subject["nsfw"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        }
    }

    private suspend fun post(
        site: CommunityExternalSite,
        url: String,
        body: JsonObject,
        parse: (JsonObject) -> List<CommunityExternalHit>,
    ): List<CommunityExternalHit> = httpClient.preparePost(url) {
        expectSuccess = false
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json")
        if (site == CommunityExternalSite.BANGUMI) header(HttpHeaders.UserAgent, userAgent)
        setBody(body.toString())
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw CommunityExternalSearchException(
                site,
                response.status.value,
                response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
            )
        }
        parse(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    /** An infobox value is a plain string or a list of `{ "v": ... }` items. */
    private fun bangumiValues(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.mapNotNull { (it as? JsonObject)?.text("v") ?: (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.jsonArray
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        const val DEFAULT_USER_AGENT = "skepsun/Kototoro (https://github.com/Kototoro-app/Kototoro)"
        const val PAGE_SIZE = 10

        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val BANGUMI_URL = "https://api.bgm.tv/v0/search/subjects"
        private const val BANGUMI_BOOK = 1
        private const val BANGUMI_ANIME = 2
        private const val BANGUMI_REAL = 6
        private const val ANILIST_QUERY = "query(\$search:String,\$type:MediaType){Page(perPage:$PAGE_SIZE)" +
            "{media(search:\$search,type:\$type){id format isAdult title{romaji english native} synonyms}}}"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
