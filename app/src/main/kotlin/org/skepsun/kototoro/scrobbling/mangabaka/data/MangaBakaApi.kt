package org.skepsun.kototoro.scrobbling.mangabaka.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import java.io.IOException
import kotlin.math.roundToInt

internal const val MANGABAKA_PAGE_SIZE = 20
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal class MangaBakaHttpException(val statusCode: Int) : IOException("MangaBaka: HTTP $statusCode")

/** Public metadata requests never carry account credentials. */
class MangaBakaApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.mangabaka.org/".toHttpUrl(),
) {
    internal suspend fun request(
        path: String,
        token: String? = null,
        method: String = "GET",
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val url = baseUrl.newBuilder().addPathSegments(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Kototoro (Android; https://github.com/Kototoro-app/Kototoro)").apply {
            if (token != null) {
                if (token.startsWith("mb-")) header("x-api-key", token)
                else header("Authorization", "Bearer $token")
                header("Cache-Control", "no-store")
            }
            method(method, body?.toString()?.toRequestBody(JSON_MEDIA_TYPE))
        }.build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw MangaBakaHttpException(response.code)
            val result = try {
                Json.parseToJsonElement(response.body.string()).jsonObject
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid MangaBaka response", e)
            }
            val status = result["status"]?.jsonPrimitive?.intOrNull
            if (status == null || status !in 200..299 || result["data"] == null) {
                throw IOException("MangaBaka returned an unsuccessful response")
            }
            result
        }
    }

    suspend fun search(query: String, offset: Int): List<ScrobblerContent> {
        if (query.isBlank() || offset < 0 || offset >= 12000) return emptyList()
        val response = request("v1/series/search", query = mapOf(
            "q" to query.trim(), "page" to (offset / MANGABAKA_PAGE_SIZE + 1).toString(),
            "limit" to MANGABAKA_PAGE_SIZE.toString(),
        ))
        return response.array("data").map { parseMangaBakaSeries(it.jsonObject) }
            .drop(offset % MANGABAKA_PAGE_SIZE)
    }

    suspend fun details(id: Long): ScrobblerContentInfo {
        val series = request("v1/series/$id")["data"]!!.jsonObject
        val content = parseMangaBakaSeries(series)
        val sourceActions = series.obj("source")?.entries.orEmpty().mapNotNull { (name, payload) ->
            val sourceId = (payload as? JsonObject)?.text("id") ?: return@mapNotNull null
            val url = when (name) {
                "anilist" -> "https://anilist.co/manga/$sourceId"
                "my_anime_list" -> "https://myanimelist.net/manga/$sourceId"
                "kitsu" -> "https://kitsu.app/manga/$sourceId"
                "manga_updates" -> "https://www.mangaupdates.com/series/$sourceId"
                "shikimori" -> "https://shikimori.one/mangas/$sourceId"
                else -> null
            } ?: return@mapNotNull null
            ScrobblerContentInfo.ExternalAction(name, url)
        }
        return ScrobblerContentInfo(
            id = content.id, name = content.name, cover = content.cover.orEmpty(), url = content.url,
            descriptionHtml = series.text("description").orEmpty(),
            contentType = if (content.mediaType == "novel") ContentType.NOVEL else ContentType.MANGA,
            // Round the percentage first so a boundary such as 64.5 displays as 6.5, not 6.4.
            score = content.score?.let { it.roundToInt() / 10f },
            authors = (series["authors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            tags = (series["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            totalEpisodes = series["total_chapters"]?.jsonPrimitive?.intOrNull,
            actions = sourceActions,
        )
    }

    internal suspend fun library(token: String): List<MangaBakaLibraryEntry> {
        // v2 explicitly defines entry + series IDs and previews; v1's list schema omits the IDs.
        val result = ArrayList<MangaBakaLibraryEntry>()
        var page = 1
        while (true) {
            val response = request("v2/my/library", token, query = mapOf("page" to "$page", "limit" to "100"))
            val items = response.array("data")
            items.forEach { item ->
                val wrapper = item.jsonObject
                result += parseMangaBakaLibraryEntry(wrapper.getValue("entry").jsonObject)
                    .copy(content = parseMangaBakaSeries(wrapper.getValue("series").jsonObject))
            }
            val pagination = response.obj("pagination") ?: throw IOException("Missing MangaBaka pagination")
            if (pagination["next"] == JsonNull) break
            if (pagination["next"] == null || items.isEmpty() || page >= 10000) {
                throw IOException("Invalid MangaBaka pagination")
            }
            // Never follow a server-provided URL with credentials.
            page++
        }
        return result
    }

    internal suspend fun entry(id: Long, token: String): MangaBakaLibraryEntry? = try {
        parseMangaBakaLibraryEntry(request("v1/my/library/$id", token)["data"]!!.jsonObject)
    } catch (e: MangaBakaHttpException) {
        if (e.statusCode == 404) null else throw e
    }

    internal suspend fun patch(id: Long, token: String, changes: JsonObject) {
        if (changes.isEmpty()) return
        val result = request("v1/my/library/$id", token, "PATCH", changes)
        if (result["data"] != JsonPrimitive(true)) throw IOException("MangaBaka did not update the library entry")
    }
}

internal data class MangaBakaLibraryEntry(
    val id: Int,
    val seriesId: Long,
    val state: String?,
    val chapter: Int,
    val rating: Float,
    val note: String?,
    val content: ScrobblerContent? = null,
)

internal fun parseMangaBakaLibraryEntry(value: JsonObject): MangaBakaLibraryEntry {
    val seriesId = value.getValue("series_id").jsonPrimitive.longOrNull
        ?: throw IOException("Missing MangaBaka series ID")
    require(seriesId > 0)
    return MangaBakaLibraryEntry(
        id = value.getValue("id").jsonPrimitive.intOrNull ?: throw IOException("Missing MangaBaka entry ID"),
        seriesId = seriesId, state = value.text("state"),
        chapter = (value["progress_chapter"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt(),
        rating = ((value["rating"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        note = value.text("note"),
    )
}

internal fun parseMangaBakaSeries(series: JsonObject): ScrobblerContent {
    val id = series.getValue("id").jsonPrimitive.longOrNull ?: throw IOException("Missing MangaBaka series ID")
    val titles = (series["titles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val title = series.text("title")
        ?: titles.firstOrNull { it["is_primary"] == JsonPrimitive(true) }?.text("title")
        ?: titles.firstOrNull()?.text("title")
        ?: throw IOException("Missing MangaBaka title")
    return ScrobblerContent(
        id = id, name = title,
        altName = series.text("native_title")?.takeIf { it != title },
        cover = series.obj("cover")?.obj("x350")?.text("x1")
            ?: series.obj("cover")?.text("x350")
            ?: series.obj("cover")?.obj("raw")?.text("url") ?: series.obj("cover")?.text("raw"),
        url = series.text("canonical_url") ?: "https://mangabaka.org/series/$id",
        mediaType = series.text("type"),
        score = series["rating"]?.jsonPrimitive?.doubleOrNull?.toFloat(), scoreMax = 100f,
    )
}

internal fun mangaBakaProgressPatch(chapter: Int): JsonObject {
    require(chapter in 0..10000)
    return buildJsonObject { put("progress_chapter", if (chapter == 0) JsonNull else JsonPrimitive(chapter)) }
}

internal fun mangaBakaInfoPatch(
    previous: MangaBakaLibraryEntry,
    rating: Float,
    state: String?,
    note: String?,
    ratingStep: Int,
): JsonObject = buildJsonObject {
    require(rating.isFinite() && rating in 0f..1f)
    if (state != null && state != previous.state) put("state", state)
    if (rating != previous.rating) {
        val step = ratingStep.takeIf { it in setOf(1, 5, 10, 20, 25) } ?: 1
        val score = ((rating * 100 / step).roundToInt() * step).coerceIn(step, 100)
        put("rating", if (rating == 0f) JsonNull else JsonPrimitive(score))
    }
    if (note != null && note != previous.note) put("note", note)
}

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
internal fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray
    ?: throw IOException("Missing MangaBaka $name array")
