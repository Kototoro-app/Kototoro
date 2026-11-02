package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Catalog protocols used by the native clients. No proxy or third-party catalog is required. */
internal class AdditionalTrackingApi(private val client: HttpClient, private val clientIds: Map<Int, String>) {
    fun catalogItem(site: PublicTrackingSite, json: JsonObject, kind: String): TrackingMetadata = when (site) {
        PublicTrackingSite.SHIKIMORI -> shikimori(json, kind)
        PublicTrackingSite.MAL -> mal(json, kind)
        PublicTrackingSite.MANGAUPDATES -> mangaUpdates(json, kind)
        PublicTrackingSite.SIMKL -> simkl(json)
        else -> error("Unsupported catalog")
    }
    suspend fun search(site: PublicTrackingSite, query: String, kind: String, page: Int): List<TrackingMetadata> =
        list(site, kind, page, query, true)

    suspend fun browse(site: PublicTrackingSite, kind: String, popular: Boolean, page: Int): List<TrackingMetadata> =
        list(site, kind, page, null, popular)

    suspend fun calendar(site: PublicTrackingSite, day: TrackingScheduleDay, tv: Boolean, page: Int): List<TrackingMetadata> {
        if (page > 1) return emptyList()
        return when (site) {
            PublicTrackingSite.SHIKIMORI -> get("https://shikimori.io/api/calendar").jsonArray
                .map { it.jsonObject }.filter { inDay(it.str("next_episode_at"), day) }
                .sortedBy { Instant.parse(it.str("next_episode_at")!!).epochSeconds }
                .map { shikimori(it.obj("anime"), "VIDEO") }
            PublicTrackingSite.SIMKL -> {
                val type = if (tv) "tv" else "anime"
                // Local dates may span two UTC months; the v2 archives contain the exact UTC airing timestamps.
                val months = listOf(day.start, day.end - 1).map { Instant.fromEpochSeconds(it).toString().take(7) }.distinct()
                val results = ArrayList<Pair<Long, TrackingMetadata>>()
                for (month in months) {
                    val year = month.substringBefore('-')
                    val number = month.substringAfter('-').toInt()
                    val data = get("https://data.simkl.in/calendar/v2/$year/$number/$type.json", site = site).jsonObject
                    val metadata = data.obj("metadata")
                    for (entry in data.arr("calendar").map { it.jsonObject }) {
                        val date = entry.str("date")
                        if (!inDay(date, day)) continue
                        val id = entry.number("simkl_id") ?: continue
                        val item = simkl(metadata.obj(id.toString()), type)
                        results += Instant.parse(date!!).epochSeconds to item
                    }
                }
                results.sortedBy { it.first }.map { it.second }
            }
            else -> error("此网站不支持每日放送")
        }
    }

    private fun inDay(date: String?, day: TrackingScheduleDay): Boolean {
        val instant = date?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() } ?: return false
        return instant >= day.start && instant < day.end
    }

    private suspend fun list(site: PublicTrackingSite, kind: String, page: Int, query: String?, popular: Boolean): List<TrackingMetadata> {
        val type = if (kind == "VIDEO") "anime" else "manga"
        return when (site) {
            PublicTrackingSite.SHIKIMORI -> {
                val value = get("https://shikimori.io/api/${type}s", mapOf(
                    "page" to "$page", "limit" to "20", "order" to if (popular) "popularity" else "ranked",
                ) + listOfNotNull(query?.let { "search" to it },
                    "kind".takeIf { kind == "NOVEL" }?.let { it to "light_novel,novel" }).toMap())
                value.jsonArray.map { shikimori(it.jsonObject, kind) }
            }
            PublicTrackingSite.MAL -> {
                val path = if (query == null) "ranking" else ""
                val params = mapOf("limit" to "20", "offset" to "${(page - 1) * 20}",
                    "fields" to "id,title,main_picture,mean,media_type") +
                    if (query == null) mapOf("ranking_type" to if (popular) "bypopularity" else "all") else mapOf("q" to query)
                get("https://api.myanimelist.net/v2/$type/$path".trimEnd('/'), params, site).jsonObject
                    .arr("data").map { mal(it.jsonObject.obj("node"), kind) }
            }
            PublicTrackingSite.MANGAUPDATES -> {
                val body = buildJsonObject {
                    put("page", page); put("perpage", 20)
                    if (query != null) put("search", query)
                    else put("orderby", if (popular) "score" else "rating")
                    if (kind == "NOVEL") put("type", buildJsonArray { add("Novel") })
                }
                val data = client.post("https://api.mangaupdates.com/v1/series/search") {
                    expectSuccess = true
                    jsonBody(body)
                }.bodyAsText()
                Json.parseToJsonElement(data).jsonObject.arr("results").map { mangaUpdates(it.jsonObject.obj("record"), kind) }
            }
            PublicTrackingSite.SIMKL -> {
                videos(query.orEmpty(), "anime", page)
            }
            else -> error("Unsupported catalog")
        }.filter { it.remoteId > 0 && it.title.isNotBlank() }
    }

    suspend fun details(site: PublicTrackingSite, id: Long, kind: String, remoteType: String = "anime"): TrackingMetadata {
        val type = if (kind == "VIDEO") "anime" else "manga"
        return when (site) {
            PublicTrackingSite.SHIKIMORI -> shikimori(get("https://shikimori.io/api/${type}s/$id").jsonObject, kind)
            PublicTrackingSite.MAL -> mal(get("https://api.myanimelist.net/v2/$type/$id", mapOf("fields" to
                "id,title,alternative_titles,main_picture,synopsis,mean,start_date,num_chapters,num_episodes,genres,authors,media_type"), site).jsonObject, kind)
            PublicTrackingSite.MANGAUPDATES -> mangaUpdates(get("https://api.mangaupdates.com/v1/series/$id").jsonObject, kind)
            PublicTrackingSite.SIMKL -> {
                require(remoteType in listOf("anime", "tv", "movies"))
                simkl(get("https://api.simkl.com/$remoteType/$id", mapOf("extended" to "full"), site).jsonObject, remoteType)
            }
            else -> error("Unsupported catalog")
        }
    }

    suspend fun videos(query: String, remoteType: String, page: Int): List<TrackingMetadata> {
        require(remoteType in listOf("anime", "tv", "movies") && page > 0)
        val text = query.trim()
        val path = if (text.isNotEmpty()) "search/${if (remoteType == "movies") "movie" else remoteType}"
            else "$remoteType/genres/all/all-types/all-countries/all-years"
        return get("https://api.simkl.com/$path", mapOf("page" to "$page", "limit" to "10", "extended" to "full") +
            if (text.isNotEmpty()) mapOf("q" to text) else emptyMap(), PublicTrackingSite.SIMKL)
            .jsonArray.map { simkl(it.jsonObject, remoteType) }.filter { it.remoteId > 0 && it.title.isNotBlank() }
    }

    private suspend fun get(url: String, params: Map<String, String> = emptyMap(), site: PublicTrackingSite? = null): JsonElement {
        val response = client.get(url) {
            params.forEach { (key, value) -> parameter(key, value) }
            if (site == PublicTrackingSite.MAL || site == PublicTrackingSite.SIMKL) {
                val id = clientIds[site.id]?.takeIf { it.isNotBlank() } ?: error("此网站缺少应用配置")
                if (site == PublicTrackingSite.MAL) header("X-MAL-CLIENT-ID", id) else {
                    parameter("client_id", id)
                    parameter("app-name", "Kototoro")
                    parameter("app-version", "1.0")
                    header("User-Agent", "Kototoro/1.0")
                }
            }
        }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        return Json.parseToJsonElement(response.bodyAsText())
    }

    private fun shikimori(j: JsonObject, kind: String): TrackingMetadata {
        val id = j.number("id") ?: 0
        val image = j.obj("image").str("original")
        return TrackingMetadata(1, id, j.str("russian") ?: j.str("name").orEmpty(),
            listOfNotNull(j.str("name")), image?.let { if (it.startsWith("/")) "https://shikimori.io$it" else it },
            cleanTrackingDescription(j.str("description")), j.decimal("score"),
            tags = j.arr("genres").mapNotNull { it.jsonObject.str("name") },
            year = j.str("aired_on")?.take(4)?.toIntOrNull(),
            totalChapters = j.integer(if (kind == "VIDEO") "episodes" else "chapters"),
            url = "https://shikimori.io/${if (kind == "VIDEO") "animes" else "mangas"}/$id",
            mediaKind = when {
                kind == "VIDEO" -> "VIDEO"
                j.str("kind") in listOf("light_novel", "novel") -> "NOVEL"
                j.str("kind") != null -> "MANGA"
                else -> kind
            })
    }

    private fun mal(j: JsonObject, kind: String): TrackingMetadata {
        val id = j.number("id") ?: 0
        val alts = j.obj("alternative_titles")
        return TrackingMetadata(3, id, j.str("title").orEmpty(),
            alts.arr("synonyms").mapNotNull { (it as? JsonPrimitive)?.contentOrNull } + listOfNotNull(alts.str("ja"), alts.str("en")),
            j.obj("main_picture").str("large") ?: j.obj("main_picture").str("medium"),
            cleanTrackingDescription(j.str("synopsis")), j.decimal("mean"),
            tags = j.arr("genres").mapNotNull { it.jsonObject.str("name") },
            authors = j.arr("authors").map { it.jsonObject.obj("node") }
                .map { listOfNotNull(it.str("first_name"), it.str("last_name")).joinToString(" ") }.filter(String::isNotBlank),
            year = j.str("start_date")?.take(4)?.toIntOrNull(),
            totalChapters = j.integer(if (kind == "VIDEO") "num_episodes" else "num_chapters"),
            url = "https://myanimelist.net/${if (kind == "VIDEO") "anime" else "manga"}/$id",
            mediaKind = when {
                kind == "VIDEO" -> "VIDEO"
                j.str("media_type") in listOf("light_novel", "novel") -> "NOVEL"
                j.str("media_type") != null -> "MANGA"
                else -> kind
            })
    }

    private fun mangaUpdates(j: JsonObject, kind: String): TrackingMetadata = TrackingMetadata(
        6, j.number("series_id") ?: 0, j.str("title").orEmpty(),
        j.arr("associated").mapNotNull { it.jsonObject.str("title") },
        j.obj("image").obj("url").str("original"), cleanTrackingDescription(j.str("description")),
        j.decimal("bayesian_rating"), tags = j.arr("genres").mapNotNull { it.jsonObject.str("genre") },
        authors = j.arr("authors").mapNotNull { it.jsonObject.str("name") }.distinct(),
        year = j.str("year")?.take(4)?.toIntOrNull(), url = j.str("url") ?: "https://www.mangaupdates.com",
        mediaKind = when {
            j.str("type")?.contains("Novel", true) == true -> "NOVEL"
            j.str("type") != null -> "MANGA"
            else -> kind
        },
    )

    private fun simkl(raw: JsonObject, remoteType: String = "anime"): TrackingMetadata {
        val j = (raw["show"] as? JsonObject) ?: (raw["movie"] as? JsonObject) ?: raw
        val id = j.obj("ids").number("simkl_id") ?: j.obj("ids").number("simkl") ?: j.number("id") ?: 0
        return TrackingMetadata(7, id, j.str("title").orEmpty(),
            (listOfNotNull(j.str("title_en"), j.str("title_romaji")) +
                j.arr("alt_titles").mapNotNull { it.jsonObject.str("name") }).distinct(),
            j.str("poster")?.let { if (it.startsWith("https://")) it else "https://simkl.in/posters/${it}_m.jpg" },
            cleanTrackingDescription(j.str("overview")), j.obj("ratings").obj("simkl").decimal("rating"),
            tags = j.arr("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            year = j.integer("year"), totalChapters = j.integer("total_episodes"),
            url = "https://simkl.com/$remoteType/$id", mediaKind = "VIDEO", remoteType = remoteType)
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
internal fun JsonObject.number(key: String) = str(key)?.toLongOrNull()
internal fun JsonObject.integer(key: String) = str(key)?.toIntOrNull()
internal fun JsonObject.decimal(key: String) = str(key)?.toFloatOrNull()?.takeIf { it.isFinite() }
internal fun HttpRequestBuilder.jsonBody(body: JsonElement) { contentType(ContentType.Application.Json); setBody(body.toString()) }

fun cleanTrackingDescription(value: String?): String? = value?.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
    ?.replace(Regex("<[^>]+>"), "")?.replace("&nbsp;", " ")?.replace("&amp;", "&")
    ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#039;", "'")
    ?.replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n")?.trim()?.takeIf(String::isNotBlank)
