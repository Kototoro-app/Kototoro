package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

data class TrackingClientConfig(val clientId: String, val clientSecret: String = "")

@Serializable
data class TrackingSession(
    val service: Int,
    val accessToken: String,
    val refreshToken: String? = null,
    val userId: String = "",
    val username: String = "",
)

/** Scores use 0..10; statuses use stable names, independent of a site's wire representation. */
data class TrackingEntry(
    val remoteId: Long,
    val entryId: Long = remoteId,
    val status: String = "PLANNED",
    val progress: Int = 0,
    val score: Float = 0f,
    val note: String = "",
    val remoteStatus: String? = null,
)

data class TrackingCollectionItem(val item: TrackingMetadata, val entry: TrackingEntry)

/** Account protocol, isolated from Keychain, UI and local source identities. */
class TrackingAccountApi(private val client: HttpClient, private val configs: Map<Int, TrackingClientConfig>) {
    private val catalog = PublicTrackingApi(client, configs.mapValues { it.value.clientId })
    fun wireStatus(service: Int, status: String, kind: String): String = status(service, status, kind)
    fun authorizationUrl(service: Int, state: String, verifier: String): String {
        val (base, _) = oauthEndpoints(service)
        val config = config(service)
        return URLBuilder(base).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirect(service))
            parameters.append("response_type", "code")
            parameters.append("state", state)
            if (service == 1) parameters.append("scope", "user_rates")
            if (service == 3) {
                parameters.append("code_challenge", verifier)
                parameters.append("code_challenge_method", "plain")
            }
        }.buildString()
    }

    suspend fun authorize(service: Int, code: String, verifier: String): TrackingSession {
        require(code.isNotBlank())
        val (_, endpoint) = oauthEndpoints(service)
        val cfg = config(service)
        val params = mapOf("grant_type" to "authorization_code", "client_id" to cfg.clientId,
            "redirect_uri" to redirect(service), "code" to code) +
            if (service == 3) mapOf("code_verifier" to verifier) else mapOf("client_secret" to cfg.clientSecret)
        val data = tokenRequest(endpoint, params, service == 7)
        return identify(tokenSession(service, data))
    }

    suspend fun login(service: Int, username: String, password: String): TrackingSession {
        require(username.isNotBlank() && password.isNotEmpty())
        val data = when (service) {
            4 -> tokenRequest("https://kitsu.app/api/oauth/token", mapOf("grant_type" to "password",
                "username" to username, "password" to password))
            6 -> request("https://api.mangaupdates.com/v1/account/login", "PUT", body = buildJsonObject {
                put("username", username); put("password", password)
            }).jsonObject
            else -> error("此网站使用浏览器授权")
        }
        val token = if (service == 6) data.obj("context").str("session_token") ?: data.str("session_token") else data.str("access_token")
        return identify(TrackingSession(service, requireNotNull(token) { "登录失败，请检查账号和密码" }, data.str("refresh_token")))
    }

    suspend fun connectToken(service: Int, token: String): TrackingSession {
        require(token.isNotBlank())
        return identify(TrackingSession(service, token.trim()))
    }

    suspend fun refresh(session: TrackingSession): TrackingSession {
        val token = session.refreshToken ?: error("授权已过期，请重新连接账号")
        val endpoint = if (session.service == 4) "https://kitsu.app/api/oauth/token" else oauthEndpoints(session.service).second
        val params = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to token)
        if (session.service != 4) {
            val cfg = config(session.service)
            params["client_id"] = cfg.clientId
            if (session.service != 3) params["client_secret"] = cfg.clientSecret
        }
        val data = tokenRequest(endpoint, params)
        return tokenSession(session.service, data).copy(userId = session.userId, username = session.username,
            refreshToken = data.str("refresh_token") ?: token)
    }

    private suspend fun identify(session: TrackingSession): TrackingSession {
        val data = when (session.service) {
            1 -> request("https://shikimori.io/api/users/whoami", session = session).jsonObject
            2 -> graphql("query { Viewer { id name } }", session).obj("Viewer")
            3 -> request("https://api.myanimelist.net/v2/users/@me", session = session).jsonObject
            4 -> request("https://kitsu.app/api/edge/users", params = mapOf("filter[self]" to "true"), session = session)
                .jsonObject.arr("data").first().jsonObject.let { it.obj("attributes") + ("id" to it.getValue("id")) }.let(::JsonObject)
            5 -> request("https://api.bgm.tv/v0/me", session = session).jsonObject
            6 -> request("https://api.mangaupdates.com/v1/account/profile", session = session).jsonObject
            7 -> request("https://api.simkl.com/users/settings", session = session).jsonObject.let {
                JsonObject(it.obj("user") + ("id" to (it.obj("account")["id"] ?: JsonNull)))
            }
            else -> error("Unsupported account")
        }
        val id = requireNotNull(data.str("id") ?: data.str("user_id")) { "网站未返回账号身份" }
        require(id.toLongOrNull()?.let { it > 0 } == true) { "网站返回了无效的账号身份" }
        return session.copy(userId = id,
            username = data.str("nickname") ?: data.str("name") ?: data.str("username") ?: id)
    }

    /** Reads before writing, so connecting a work never resets existing progress or rating. */
    suspend fun entry(session: TrackingSession, id: Long, kind: String): TrackingEntry? {
        require(id > 0 && site(session).supports(if (kind.startsWith("VIDEO")) "VIDEO" else kind))
        val type = if (kind == "VIDEO") "anime" else "manga"
        return when (session.service) {
            1 -> request("https://shikimori.io/api/v2/user_rates", params = mapOf("user_id" to session.userId,
                "target_id" to "$id", "target_type" to if (kind == "VIDEO") "Anime" else "Manga"), session = session)
                .jsonArray.firstOrNull()?.jsonObject?.let { parseEntry(1, id, it, kind) }
            2 -> graphql("query { MediaList(userId: ${session.userId}, mediaId: $id) { id status progress score(format: POINT_10) notes } }",
                session, allowMissing = true)["MediaList"]?.takeIf { it is JsonObject }?.jsonObject?.let { parseEntry(2, id, it, kind) }
            3 -> request("https://api.myanimelist.net/v2/$type/$id", params = mapOf("fields" to "my_list_status"), session = session)
                .jsonObject["my_list_status"]?.takeIf { it is JsonObject }?.jsonObject?.let { parseEntry(3, id, it, kind) }
            4 -> request("https://kitsu.app/api/edge/library-entries", params = mapOf("filter[userId]" to session.userId,
                "filter[${type}Id]" to "$id"), session = session).jsonObject.arr("data").firstOrNull()?.jsonObject
                ?.let { parseEntry(4, id, it, kind) }
            5 -> request("https://api.bgm.tv/v0/users/${session.userId}/collections/$id", session = session, missing = true)
                .takeIf { it is JsonObject }?.jsonObject?.let { parseEntry(5, id, it, kind) }
            // MU and SIMKL expose the user's list rather than a single entry endpoint.
            6 -> mangaUpdatesEntry(session, id)
            7 -> simklEntry(session, id, kind)
            else -> error("Unsupported account")
        }
    }

    /** Lists stay remote until the user selects a local work; fetching does not import or overwrite library records. */
    suspend fun collection(session: TrackingSession, kind: String, status: String, page: Int): List<TrackingCollectionItem> {
        require(page > 0 && site(session).supports(if (kind.startsWith("VIDEO")) "VIDEO" else kind))
        val wire = status(session.service, status, kind)
        val type = if (kind == "VIDEO") "anime" else "manga"
        val offset = (page - 1) * 20
        fun mapped(media: JsonObject, rate: JsonObject, mediaKind: String = kind): TrackingCollectionItem {
            val item = catalog.catalogItem(site(session), media, mediaKind)
            return TrackingCollectionItem(item, parseEntry(session.service, item.remoteId, rate, mediaKind))
        }
        return when (session.service) {
            1 -> request("https://shikimori.io/api/users/${session.userId}/${type}_rates", params = mapOf(
                "page" to "$page", "limit" to "20", "status" to wire), session = session).jsonArray
                .map { it.jsonObject }.map { mapped(it.obj(type), it) }
            2 -> graphql("""query { Page(page: $page, perPage: 20) { mediaList(userId: ${session.userId},
                type: ${if (kind == "VIDEO") "ANIME" else "MANGA"}, status: $wire, sort: UPDATED_TIME_DESC) {
                id status progress score(format: POINT_10) notes
                media { id type format title { userPreferred native } coverImage { large } siteUrl } } } }""", session)
                .obj("Page").arr("mediaList").map { it.jsonObject }.map { mapped(it.obj("media"), it) }
            3 -> request("https://api.myanimelist.net/v2/users/@me/${type}list", params = mapOf("limit" to "20", "offset" to "$offset",
                "status" to wire, "fields" to "list_status,media_type"), session = session).jsonObject.arr("data")
                .map { it.jsonObject }.map { mapped(it.obj("node"), it.obj("list_status")) }
            4 -> {
                val data = request("https://kitsu.app/api/edge/library-entries", params = mapOf("filter[userId]" to session.userId,
                    "filter[kind]" to type, "filter[status]" to wire, "include" to type, "page[limit]" to "20",
                    "page[offset]" to "$offset"), session = session).jsonObject
                val included = data.arr("included").map { it.jsonObject }.associateBy { it.str("id") }
                data.arr("data").mapNotNull { raw ->
                    val rate = raw.jsonObject
                    val mediaId = rate.obj("relationships").obj(type).obj("data").str("id")
                    included[mediaId]?.let { mapped(it, rate) }
                }
            }
            5 -> request("https://api.bgm.tv/v0/users/${session.userId}/collections", params = mapOf("type" to wire,
                "subject_type" to if (kind == "VIDEO") "2" else "1", "limit" to "20", "offset" to "$offset"), session = session)
                .jsonObject.arr("data").map { it.jsonObject }.map { mapped(it.obj("subject"), it) }
            6 -> request("https://api.mangaupdates.com/v1/lists/$wire/search", "POST", session = session,
                body = buildJsonObject { put("page", page); put("perpage", 20) }).jsonObject.arr("results")
                .map { it.jsonObject.obj("record") }.map { record ->
                    val series = record.obj("series")
                    val id = series.number("id") ?: 0
                    val metadata = TrackingMetadata(6, id, series.str("title").orEmpty(),
                        url = series.str("url").orEmpty(), mediaKind = kind)
                    TrackingCollectionItem(metadata, TrackingEntry(id, status = status, progress = record.obj("status").integer("chapter") ?: 0))
                }
            7 -> {
                val scope = simklScope(kind)
                val data = request("https://api.simkl.com/sync/all-items/$scope/$wire", session = session).jsonObject
                data.arr(scope).drop(offset).take(20).map { it.jsonObject }.map { rate ->
                    val raw = rate.obj(if (scope == "movies") "movie" else "show")
                    val item = catalog.catalogItem(site(session), raw, "VIDEO").copy(remoteType = simklDomain(kind),
                        url = "https://simkl.com/${simklDomain(kind)}/${raw.obj("ids").number("simkl")}")
                    TrackingCollectionItem(item, TrackingEntry(item.remoteId, status = status,
                        progress = if (scope == "movies") { if (status == "COMPLETED") 1 else 0 }
                            else rate.integer("watched_episodes_count") ?: 0, score = rate.decimal("user_rating") ?: 0f))
                }
            }
            else -> error("Unsupported account")
        }.filter { it.item.remoteId > 0 && it.item.title.isNotBlank() }
    }

    suspend fun update(session: TrackingSession, kind: String, value: TrackingEntry, existing: Boolean): TrackingEntry {
        require(value.remoteId > 0 && value.progress in 0..100_000 && value.score.isFinite() && value.score in 0f..10f)
        require(site(session).supports(if (kind.startsWith("VIDEO")) "VIDEO" else kind))
        if (session.service == 7 && kind == "VIDEO_MOVIE") require(value.progress <= 1)
        val status = status(session.service, value.status, kind)
        val id = value.remoteId
        val type = if (kind == "VIDEO") "anime" else "manga"
        when (session.service) {
            1 -> {
                val rate = buildJsonObject {
                    put("target_id", id); put("target_type", if (kind == "VIDEO") "Anime" else "Manga")
                    put("user_id", session.userId.toLong()); put("status", status); put("score", value.score.roundToInt())
                    put(if (kind == "VIDEO") "episodes" else "chapters", value.progress); put("text", value.note)
                }
                val result = request("https://shikimori.io/api/v2/user_rates${if (existing) "/${value.entryId}" else ""}",
                    if (existing) "PATCH" else "POST", session = session, body = buildJsonObject { put("user_rate", rate) })
                return parseEntry(1, id, result.jsonObject, kind)
            }
            2 -> {
                val result = graphql("""mutation { SaveMediaListEntry(mediaId: $id, status: $status,
                    progress: ${value.progress}, scoreRaw: ${(value.score * 10).roundToInt()}, notes: ${JsonPrimitive(value.note)}) {
                    id status progress score(format: POINT_10) notes } }""", session)
                return parseEntry(2, id, result.obj("SaveMediaListEntry"), kind)
            }
            3 -> {
                val fields = mapOf("status" to status, "score" to "${value.score.roundToInt()}",
                    (if (kind == "VIDEO") "num_watched_episodes" else "num_chapters_read") to "${value.progress}", "comments" to value.note)
                request("https://api.myanimelist.net/v2/$type/$id/my_list_status", "PUT", session = session, form = fields)
            }
            4 -> {
                val data = buildJsonObject {
                    put("type", "libraryEntries")
                    if (existing) put("id", value.entryId.toString())
                    put("attributes", buildJsonObject {
                        put("status", status); put("progress", value.progress); put("notes", value.note)
                        put("ratingTwenty", if (value.score == 0f) JsonNull else JsonPrimitive((value.score * 2).roundToInt().coerceIn(2, 20)))
                    })
                    if (!existing) put("relationships", buildJsonObject {
                        put(type, buildJsonObject { put("data", buildJsonObject { put("type", type); put("id", "$id") }) })
                        put("user", buildJsonObject { put("data", buildJsonObject { put("type", "users"); put("id", session.userId) }) })
                    })
                }
                val result = request("https://kitsu.app/api/edge/library-entries${if (existing) "/${value.entryId}" else ""}",
                    if (existing) "PATCH" else "POST", session = session, body = buildJsonObject { put("data", data) })
                return parseEntry(4, id, result.jsonObject.obj("data"), kind)
            }
            5 -> request("https://api.bgm.tv/v0/users/-/collections/$id", if (existing) "PATCH" else "POST",
                session = session, body = buildJsonObject {
                    put("type", status.toInt()); put("ep_status", value.progress); put("rate", value.score.roundToInt()); put("comment", value.note)
                })
            6 -> {
                val item = buildJsonArray { add(buildJsonObject {
                    put("series", buildJsonObject { put("id", id) }); put("list_id", status.toInt())
                    put("status", buildJsonObject { put("chapter", value.progress) })
                }) }
                request("https://api.mangaupdates.com/v1/lists/series${if (existing) "/update" else ""}", "POST", session = session, body = item)
                request("https://api.mangaupdates.com/v1/series/$id/rating", if (value.score == 0f) "DELETE" else "PUT",
                    session = session, body = if (value.score == 0f) null else buildJsonObject { put("rating", value.score.roundToInt()) },
                    missing = value.score == 0f)
            }
            7 -> {
                val container = if (kind == "VIDEO_MOVIE") "movies" else "shows"
                request("https://api.simkl.com/sync/add-to-list", "POST", session = session, body = buildJsonObject {
                    put(container, buildJsonArray { add(buildJsonObject {
                        put("to", status); put("ids", buildJsonObject { put("simkl", id) })
                    }) })
                })
                if (value.progress > 0) request("https://api.simkl.com/sync/history", "POST", session = session, body = buildJsonObject {
                    put(container, buildJsonArray { add(buildJsonObject {
                        put("ids", buildJsonObject { put("simkl", id) })
                        if (kind != "VIDEO_MOVIE") put("episodes", buildJsonArray { for (episode in 1..value.progress) add(buildJsonObject { put("number", episode) }) })
                    }) })
                })
                request("https://api.simkl.com/sync/ratings${if (value.score == 0f) "/remove" else ""}", "POST", session = session,
                    body = buildJsonObject { put(container, buildJsonArray { add(buildJsonObject {
                        put("ids", buildJsonObject { put("simkl", id) }); if (value.score > 0) put("rating", value.score.roundToInt())
                    }) }) }, missing = value.score == 0f)
            }
        }
        return value
    }

    /** Automatic updates intentionally carry only progress. They must not rewrite user-edited remote fields. */
    suspend fun advance(session: TrackingSession, kind: String, entry: TrackingEntry, progress: Int): TrackingEntry {
        require(progress > entry.progress && progress <= 100_000)
        val id = entry.remoteId
        val anime = kind == "VIDEO"
        when (session.service) {
            1 -> request("https://shikimori.io/api/v2/user_rates/${entry.entryId}", "PATCH", session = session,
                body = buildJsonObject { put("user_rate", buildJsonObject { put(if (anime) "episodes" else "chapters", progress) }) })
            2 -> graphql("mutation { SaveMediaListEntry(mediaId: $id, progress: $progress) { id } }", session)
            3 -> request("https://api.myanimelist.net/v2/${if (anime) "anime" else "manga"}/$id/my_list_status", "PUT",
                session = session, form = mapOf((if (anime) "num_watched_episodes" else "num_chapters_read") to "$progress"))
            4 -> request("https://kitsu.app/api/edge/library-entries/${entry.entryId}", "PATCH", session = session,
                body = buildJsonObject { put("data", buildJsonObject {
                    put("id", entry.entryId.toString()); put("type", "libraryEntries")
                    put("attributes", buildJsonObject { put("progress", progress) })
                }) })
            5 -> request("https://api.bgm.tv/v0/users/-/collections/$id", "PATCH", session = session,
                body = buildJsonObject { put("ep_status", progress) })
            6 -> request("https://api.mangaupdates.com/v1/lists/series/update", "POST", session = session,
                body = buildJsonArray { add(buildJsonObject {
                    put("series", buildJsonObject { put("id", id) })
                    put("list_id", entry.remoteStatus?.toLongOrNull() ?: status(6, entry.status, kind).toLong())
                    put("status", buildJsonObject { put("chapter", progress) })
                }) })
            7 -> request("https://api.simkl.com/sync/history", "POST", session = session, body = buildJsonObject {
                put(if (kind == "VIDEO_MOVIE") "movies" else "shows", buildJsonArray { add(buildJsonObject {
                    put("ids", buildJsonObject { put("simkl", id) })
                    if (kind != "VIDEO_MOVIE") put("episodes", buildJsonArray { for (episode in entry.progress + 1..progress) add(buildJsonObject { put("number", episode) }) })
                }) })
            })
        }
        return entry.copy(progress = progress)
    }

    private suspend fun mangaUpdatesEntry(session: TrackingSession, id: Long): TrackingEntry? {
        val lists = request("https://api.mangaupdates.com/v1/lists", session = session).jsonArray
        for (list in lists) {
            val record = list.jsonObject
            val listId = record.number("list_id") ?: continue
            var page = 1
            while (true) {
                val items = request("https://api.mangaupdates.com/v1/lists/$listId/search", "POST", session = session,
                    body = buildJsonObject { put("page", page); put("perpage", 100) }).jsonObject.arr("results")
                val found = items.map { it.jsonObject.obj("record") }.firstOrNull { it.obj("series").number("id") == id }
                if (found != null) {
                    val rating = request("https://api.mangaupdates.com/v1/series/$id/rating", session = session, missing = true)
                    val score = (rating as? JsonObject)?.let { it.decimal("rating") ?: it.decimal("user_rating") } ?: 0f
                    return TrackingEntry(id, status = canonical(6, "$listId"), progress = found.obj("status").integer("chapter") ?: 0,
                        score = score, remoteStatus = "$listId")
                }
                if (items.size < 100) break
                page++
            }
        }
        return null
    }

    private suspend fun simklEntry(session: TrackingSession, id: Long, kind: String): TrackingEntry? {
        val scope = simklScope(kind)
        val data = request("https://api.simkl.com/sync/all-items/$scope", session = session).jsonObject
        val entry = data.arr(scope).map { it.jsonObject }.firstOrNull {
            it.obj(if (scope == "movies") "movie" else "show").obj("ids").number("simkl") == id
        } ?: return null
        return TrackingEntry(id, status = canonical(7, entry.str("status")),
            progress = if (scope == "movies") { if (entry.str("status") == "completed") 1 else 0 }
                else entry.integer("watched_episodes_count") ?: 0,
            score = entry.decimal("user_rating") ?: 0f)
    }

    private fun simklScope(kind: String) = when (kind) { "VIDEO_TV" -> "shows"; "VIDEO_MOVIE" -> "movies"; else -> "anime" }
    private fun simklDomain(kind: String) = when (kind) { "VIDEO_TV" -> "tv"; "VIDEO_MOVIE" -> "movies"; else -> "anime" }

    private fun parseEntry(service: Int, id: Long, raw: JsonObject, kind: String): TrackingEntry {
        val j = if (service == 4) raw.obj("attributes") else raw
        return TrackingEntry(id, raw.number("id") ?: id, canonical(service, j.str(if (service == 5) "type" else "status")),
            j.integer(when (service) { 1 -> if (kind == "VIDEO") "episodes" else "chapters"
                3 -> if (kind == "VIDEO") "num_episodes_watched" else "num_chapters_read"
                5 -> "ep_status"; else -> "progress" }) ?: 0,
            (j.decimal(when (service) { 4 -> "ratingTwenty"; 5 -> "rate"; else -> "score" }) ?: 0f) / if (service == 4) 2f else 1f,
            j.str(when (service) { 1 -> "text"; 3 -> "comments"; 5 -> "comment"; else -> "notes" }).orEmpty())
    }

    private fun status(service: Int, name: String, kind: String): String {
        val index = listOf("PLANNED", "READING", "COMPLETED", "ON_HOLD", "DROPPED").indexOf(name)
        if (name == "RE_READING" && service in listOf(1, 2)) return if (service == 1) "rewatching" else "REPEATING"
        require(index >= 0) { "无效的追踪状态" }
        return statuses(service, kind)[index]
    }

    private fun canonical(service: Int, value: String?): String {
        if (value in listOf("rewatching", "REPEATING")) return "RE_READING"
        val index = statuses(service, "MANGA").indexOf(value).takeIf { it >= 0 }
            ?: statuses(service, "VIDEO").indexOf(value).takeIf { it >= 0 } ?: 0
        return listOf("PLANNED", "READING", "COMPLETED", "ON_HOLD", "DROPPED")[index]
    }

    private fun statuses(service: Int, kind: String): List<String> = when (service) {
        1 -> listOf("planned", "watching", "completed", "on_hold", "dropped")
        2 -> listOf("PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED")
        3 -> if (kind == "VIDEO") listOf("plan_to_watch", "watching", "completed", "on_hold", "dropped")
            else listOf("plan_to_read", "reading", "completed", "on_hold", "dropped")
        4 -> listOf("planned", "current", "completed", "on_hold", "dropped")
        5 -> listOf("1", "3", "2", "4", "5")
        6 -> listOf("1", "0", "2", "4", "3")
        7 -> listOf("plantowatch", "watching", "completed", "hold", "dropped")
        else -> error("Unsupported account")
    }

    private suspend fun graphql(query: String, session: TrackingSession, allowMissing: Boolean = false): JsonObject {
        val response = request("https://graphql.anilist.co", "POST", session = session,
            body = buildJsonObject { put("query", query) }, missing = allowMissing)
        if (allowMissing && response is JsonNull) return JsonObject(emptyMap())
        val result = response.jsonObject
        val errors = result.arr("errors")
        check(errors.isEmpty() || allowMissing && errors.all { it.jsonObject.integer("status") == 404 }) { "AniList 请求失败" }
        return result.obj("data")
    }

    private suspend fun tokenRequest(endpoint: String, params: Map<String, String>, json: Boolean = false): JsonObject =
        request(endpoint, "POST", body = if (json) JsonObject(params.mapValues { JsonPrimitive(it.value) }) else null,
            form = if (json) null else params).jsonObject

    private suspend fun request(url: String, method: String = "GET", params: Map<String, String> = emptyMap(),
        session: TrackingSession? = null, body: JsonElement? = null, form: Map<String, String>? = null, missing: Boolean = false): JsonElement {
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            expectSuccess = !missing
            params.forEach { (key, value) -> parameter(key, value) }
            header("User-Agent", "Kototoro/1.0 (https://github.com/skepsun/Kototoro)")
            if (session != null) {
                bearerAuth(session.accessToken)
                if (session.service == 7) header("simkl-api-key", config(7).clientId)
                if (session.service == 4) { header("Accept", "application/vnd.api+json"); contentType(ContentType.parse("application/vnd.api+json")) }
            }
            if (body != null) {
                jsonBody(body)
                if (session?.service == 4) contentType(ContentType.parse("application/vnd.api+json"))
            }
            if (form != null) setBody(FormDataContent(Parameters.build { form.forEach { (key, value) -> append(key, value) } }))
        }
        if (missing && response.status == HttpStatusCode.NotFound) return JsonNull
        if (!response.status.isSuccess()) throw io.ktor.client.plugins.ClientRequestException(response, "")
        val text = response.bodyAsText()
        val result = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
        if (result is JsonObject) {
            check(result.str("status") != "exception") { "网站拒绝了本次操作，请重新读取记录后重试" }
            val missingItems = result["not_found"] as? JsonObject
            check(missing || missingItems == null || missingItems.values.none { it is JsonArray && it.isNotEmpty() }) {
                "网站找不到所选作品，请重新选择关联条目"
            }
        }
        return result
    }

    private fun tokenSession(service: Int, data: JsonObject) = TrackingSession(service,
        requireNotNull(data.str("access_token")) { "网站未返回有效授权" }, data.str("refresh_token"))
    private fun site(session: TrackingSession) = PublicTrackingSite.entries.first { it.id == session.service }
    private fun config(service: Int) = configs[service]?.takeIf { it.clientId.isNotBlank() } ?: error("此网站缺少应用授权配置")
    private fun redirect(service: Int) = "kototoro://${when (service) { 1 -> "shikimori"; 2 -> "anilist"; 3 -> "mal"; 5 -> "bangumi"; 7 -> "simkl"; else -> error("Unsupported OAuth") }}-auth"
    private fun oauthEndpoints(service: Int): Pair<String, String> = when (service) {
        1 -> "https://shikimori.io/oauth/authorize" to "https://shikimori.io/oauth/token"
        2 -> "https://anilist.co/api/v2/oauth/authorize" to "https://anilist.co/api/v2/oauth/token"
        3 -> "https://myanimelist.net/v1/oauth2/authorize" to "https://myanimelist.net/v1/oauth2/token"
        5 -> "https://bgm.tv/oauth/authorize" to "https://bgm.tv/oauth/access_token"
        7 -> "https://simkl.com/oauth/authorize" to "https://api.simkl.com/oauth/token"
        else -> error("此网站使用账号密码登录")
    }
}
