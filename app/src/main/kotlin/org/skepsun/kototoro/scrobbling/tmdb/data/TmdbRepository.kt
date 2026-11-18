package org.skepsun.kototoro.scrobbling.tmdb.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByManga
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingByTargetId
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByManga
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingForManga
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val REDIRECT_URI = "kototoro://tmdb-auth"
private const val BASE_API_URL = "https://api.themoviedb.org/3"
private const val BASE_WEB_URL = "https://www.themoviedb.org"
private const val IMAGE_URL = "https://image.tmdb.org/t/p/w500"
private const val AVATAR_URL = "https://image.tmdb.org/t/p/w185"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** TMDB rates 0.5..10 in half steps; the app's rating is 0..1. */
private const val MAX_RATING = 10f

/**
 * TMDB account tracking over the v3 API: sign-in through its request-token flow, the watchlist as the status, and
 * ratings. Movies and TV shows have separate id spaces, so a title's id here is `tmdbId * 2`, plus one for a show;
 * [encodeId]/[decodeId] keep that in one place.
 */
@Singleton
class TmdbRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ScrobblerType(ScrobblerService.TMDB) private val okHttp: OkHttpClient,
    @ScrobblerType(ScrobblerService.TMDB) private val storage: ScrobblerStorage,
    private val db: MangaDatabase,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

    private val apiKey = context.getString(R.string.tmdb_apiKey)

    /**
     * The sign-in page needs a request token fetched first, so the page is built by [createAuthorizationUrl]; callers
     * that only have this property get the same page, at the cost of waiting for that request.
     */
    override val oauthUrl: String
        get() = runBlocking(Dispatchers.IO) { createAuthorizationUrl() }

    suspend fun createAuthorizationUrl(): String {
        check(apiKey.isNotBlank()) { "Missing TMDB API key" }
        val token = get("/authentication/token/new").getString("request_token")
        return "$BASE_WEB_URL/authenticate/$token?redirect_to=$REDIRECT_URI"
    }

    override val isAuthorized: Boolean
        get() = storage.accessToken != null

    override val cachedUser: ScrobblerUser?
        get() = storage.user

    /** [code] is the approved request token TMDB hands back on the redirect. */
    override suspend fun authorize(code: String?) {
        require(!code.isNullOrBlank()) { "TMDB request token is missing" }
        val session = post("/authentication/session/new", JSONObject().put("request_token", code))
        check(session.optBoolean("success", true)) { "TMDB did not accept the request token" }
        storage.accessToken = session.getString("session_id")
    }

    override suspend fun loadUser(): ScrobblerUser = loadUserProfile().user

    override suspend fun loadUserProfile(): ScrobblerUserProfile {
        val account = get("/account")
        val avatarPath = account.optJSONObject("avatar")?.optJSONObject("tmdb")?.optString("avatar_path")
            ?.takeIf { it.isNotBlank() }
        val user = ScrobblerUser(
            id = account.getLong("id"),
            nickname = account.optString("username").ifBlank { account.optString("name") },
            avatar = avatarPath?.let { AVATAR_URL + it },
            service = ScrobblerService.TMDB,
        ).also { storage.user = it }
        return ScrobblerUserProfile(user = user)
    }

    override fun logout() {
        storage.clear()
    }

    override suspend fun unregister(mangaId: Long) {
        db.deleteScrobblingByManga(ScrobblerService.TMDB.id, mangaId)
    }

    override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
        val page = offset / PAGE_SIZE + 1
        val response = get(
            "/search/multi",
            "query" to query,
            "page" to page.toString(),
            "include_adult" to "false",
        )
        return response.getJSONArray("results").mapJSONNotNull { it.toContent(defaultIsTv = null) }
    }

    /** One title of a TMDB list; [defaultIsTv] says what lists that do not name the media type (trending/popular) hold. */
    private fun JSONObject.toContent(defaultIsTv: Boolean?): ScrobblerContent? {
        val isTv = when (optString("media_type")) {
            "tv" -> true
            "movie" -> false
            else -> defaultIsTv ?: return null
        }
        val title = optString(if (isTv) "name" else "title").ifBlank { return null }
        val original = optString(if (isTv) "original_name" else "original_title")
        val date = optString(if (isTv) "first_air_date" else "release_date")
        val id = getLong("id")
        return ScrobblerContent(
            id = encodeId(id, isTv),
            name = title,
            altName = original.takeIf { it.isNotBlank() && it != title },
            cover = optString("poster_path").takeIf { it.isNotBlank() }?.let { IMAGE_URL + it },
            url = webUrl(id, isTv),
            mediaType = mediaTypeOf(isTv),
            subtitle = date.take(4).takeIf { it.isNotBlank() },
            score = optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            scoreMax = MAX_RATING,
        )
    }

    /** The titles of one of TMDB's discovery lists (see [DISCOVERY_CATEGORIES]); [page] starts at 0. */
    suspend fun getDiscoveryItems(categoryId: String, page: Int): List<ScrobblerContent> {
        val category = DISCOVERY_CATEGORIES[categoryId] ?: return emptyList()
        val response = get(category.first, "page" to (page + 1).toString())
        return response.optJSONArray("results")?.mapJSONNotNull { it.toContent(defaultIsTv = category.second) }.orEmpty()
    }

    override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
        val (tmdbId, isTv) = decodeId(id)
        val details = get(
            "/${path(isTv)}/$tmdbId",
            "append_to_response" to "credits,recommendations,external_ids",
        )
        val title = details.optString(if (isTv) "name" else "title")
        val genres = details.optJSONArray("genres")?.mapJSONNotNull { it.optString("name").takeIf(String::isNotBlank) }
            .orEmpty()
        val credits = details.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.mapJSONNotNull { person ->
            val name = person.optString("name").takeIf(String::isNotBlank) ?: return@mapJSONNotNull null
            ScrobblerContentInfo.CharacterInfo(
                id = person.optLong("id"),
                name = person.optString("character").takeIf(String::isNotBlank) ?: name,
                coverUrl = person.optString("profile_path").takeIf { it.isNotBlank() }?.let { AVATAR_URL + it }.orEmpty(),
                role = name,
                url = "$BASE_WEB_URL/person/${person.optLong("id")}",
            )
        }.orEmpty().take(MAX_CAST)
        val crew = credits?.optJSONArray("crew")?.mapJSONNotNull { person ->
            val job = person.optString("job")
            if (job != "Director" && job != "Writer" && job != "Screenplay" && job != "Creator") return@mapJSONNotNull null
            ScrobblerContentInfo.PersonInfo(
                id = person.optLong("id"),
                name = person.optString("name").takeIf(String::isNotBlank) ?: return@mapJSONNotNull null,
                avatarUrl = person.optString("profile_path").takeIf { it.isNotBlank() }?.let { AVATAR_URL + it },
                url = "$BASE_WEB_URL/person/${person.optLong("id")}",
                role = job,
            )
        }.orEmpty().distinctBy { it.id to it.role }
        val creators = details.optJSONArray("created_by")?.mapJSONNotNull { it.optString("name").takeIf(String::isNotBlank) }
            .orEmpty()
        val recommendations = details.optJSONObject("recommendations")?.optJSONArray("results")
            ?.mapJSONNotNull { item ->
                val content = item.toContent(defaultIsTv = isTv) ?: return@mapJSONNotNull null
                ScrobblerContentInfo.RelatedWork(
                    id = content.id,
                    title = content.name,
                    coverUrl = content.cover.orEmpty(),
                    relationship = content.subtitle,
                    url = content.url,
                )
            }.orEmpty()
        val facts = buildList {
            details.optString("status").takeIf(String::isNotBlank)?.let { add("Status" to it) }
            details.optString(if (isTv) "first_air_date" else "release_date").takeIf(String::isNotBlank)
                ?.let { add("Release date" to it) }
            details.optString("original_language").takeIf(String::isNotBlank)?.let { add("Original language" to it) }
            details.optInt("runtime").takeIf { it > 0 }?.let { add("Runtime" to "$it min") }
            if (isTv) {
                details.optInt("number_of_seasons").takeIf { it > 0 }?.let { add("Seasons" to it.toString()) }
                details.optJSONArray("networks")?.mapJSONNotNull { it.optString("name").takeIf(String::isNotBlank) }
                    ?.takeIf { it.isNotEmpty() }?.let { add("Networks" to it.joinToString()) }
            }
            details.optString("tagline").takeIf(String::isNotBlank)?.let { add("Tagline" to it) }
            details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf(String::isNotBlank)
                ?.let { add("IMDb" to it) }
        }
        return ScrobblerContentInfo(
            id = id,
            name = title,
            cover = details.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMAGE_URL + it }.orEmpty(),
            url = webUrl(tmdbId, isTv),
            descriptionHtml = details.optString("overview"),
            contentType = ContentType.VIDEO,
            score = details.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            tags = genres,
            authors = (creators + cast.map { it.role.orEmpty() }).filter { it.isNotBlank() }.distinct().take(MAX_CAST),
            staff = crew,
            totalEpisodes = if (isTv) details.optInt("number_of_episodes").takeIf { it > 0 } else null,
            infoboxProperties = facts,
            characters = cast,
            recommendations = recommendations,
        )
    }

    override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
        setWatchlist(content.id, true)
        saveRate(
            mangaId = mangaId,
            targetId = content.id,
            status = STATUS_WATCHLIST,
            chapter = 0,
            comment = null,
            rating = 0f,
            preview = content,
        )
    }

    /** TMDB has no watch progress, so an episode count changes nothing on the account. */
    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) = Unit

    override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
        val entity = requireNotNull(db.findScrobblingByManga(ScrobblerService.TMDB.id, mangaId)) {
            "Scrobbling info for manga $mangaId not found"
        }
        val resolvedStatus = status ?: entity.status
        if (resolvedStatus != entity.status && resolvedStatus != null) {
            // Planned titles sit on the watchlist; any other state means it was started, watched or given up.
            setWatchlist(entity.targetId, resolvedStatus == STATUS_WATCHLIST)
        }
        if (rating != entity.rating) {
            val (tmdbId, isTv) = decodeId(entity.targetId)
            if (rating > 0f) {
                val value = ((rating.coerceIn(0f, 1f) * MAX_RATING * 2).roundToInt() / 2f).coerceAtLeast(0.5f)
                post("/${path(isTv)}/$tmdbId/rating", JSONObject().put("value", value))
            } else {
                delete("/${path(isTv)}/$tmdbId/rating")
            }
        }
        saveRate(mangaId, entity.targetId, resolvedStatus, entity.chapter, comment ?: entity.comment, rating, existing = entity)
    }

    /**
     * Mirrors the account's watchlist and ratings into the local tracking table. Titles without a local counterpart
     * stay as remote-only rows; the local link, progress and comment of the ones that have one are kept, and remote-only
     * rows that left the account are dropped.
     */
    suspend fun syncLibraryFromRemote(): Int {
        val account = storage.user?.id ?: loadUser().id
        val existing = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.TMDB.id).preferredScrobblingByTargetId()
        val remote = LinkedHashMap<Long, ScrobblingEntity>()
        for (isTv in listOf(false, true)) {
            val type = if (isTv) "tv" else "movies"
            for (item in getAllPages("/account/$account/watchlist/$type")) {
                val content = item.toContent(defaultIsTv = isTv) ?: continue
                remote[content.id] = content.toEntity(existing[content.id], STATUS_WATCHLIST, rating = 0f)
            }
            for (item in getAllPages("/account/$account/rated/$type")) {
                val content = item.toContent(defaultIsTv = isTv) ?: continue
                val rating = (item.optDouble("rating").takeIf { !it.isNaN() } ?: 0.0).toFloat() / MAX_RATING
                val onList = remote[content.id]
                // A rated title that is not on the watchlist has been watched.
                remote[content.id] = onList?.copy(rating = rating)
                    ?: content.toEntity(existing[content.id], STATUS_WATCHED, rating)
            }
        }
        db.withTransaction {
            val dao = db.getScrobblingDao()
            for (entity in existing.values) {
                if (entity.mangaId == 0L && entity.targetId !in remote) dao.delete(entity)
            }
            for (entity in remote.values) db.upsertScrobbling(entity)
        }
        return remote.size
    }

    private fun ScrobblerContent.toEntity(existing: ScrobblingEntity?, status: String, rating: Float) = ScrobblingEntity(
        scrobbler = ScrobblerService.TMDB.id,
        id = id.toInt(),
        mangaId = existing?.mangaId ?: 0L,
        targetId = id,
        status = status,
        chapter = existing?.chapter ?: 0,
        comment = existing?.comment,
        rating = rating.coerceIn(0f, 1f),
        mediaType = mediaType.orEmpty(),
        remoteTitle = name,
        remoteCoverUrl = cover,
        remoteUrl = url,
    )

    private suspend fun getAllPages(path: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        var page = 1
        while (page <= MAX_SYNC_PAGES) {
            val response = get(path, "page" to page.toString())
            response.optJSONArray("results")?.mapJSONNotNull { it }?.let(result::addAll)
            if (page >= response.optInt("total_pages", 1)) break
            page++
        }
        return result
    }

    private suspend fun setWatchlist(id: Long, add: Boolean) {
        val (tmdbId, isTv) = decodeId(id)
        val account = storage.user?.id ?: loadUser().id
        post(
            "/account/$account/watchlist",
            JSONObject()
                .put("media_type", if (isTv) "tv" else "movie")
                .put("media_id", tmdbId)
                .put("watchlist", add),
        )
    }

    private suspend fun saveRate(
        mangaId: Long,
        targetId: Long,
        status: String?,
        chapter: Int,
        comment: String?,
        rating: Float,
        preview: ScrobblerContent? = null,
        existing: ScrobblingEntity? = null,
    ) {
        db.upsertScrobblingForManga(
            ScrobblingEntity(
                scrobbler = ScrobblerService.TMDB.id,
                id = targetId.toInt(),
                mangaId = mangaId,
                targetId = targetId,
                status = status,
                chapter = chapter,
                comment = comment,
                rating = rating.coerceIn(0f, 1f),
                mediaType = preview?.mediaType ?: existing?.mediaType ?: mediaTypeOf(decodeId(targetId).second),
                remoteTitle = preview?.name ?: existing?.remoteTitle,
                remoteCoverUrl = preview?.cover ?: existing?.remoteCoverUrl,
                remoteUrl = preview?.url ?: existing?.remoteUrl,
            ),
            mangaId = mangaId,
        )
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>): JSONObject {
        val url = (BASE_API_URL + path).toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
    }

    private suspend fun delete(path: String): JSONObject {
        val request = Request.Builder().url(BASE_API_URL + path).delete().build()
        return okHttp.newCall(request).await().parseJson()
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(BASE_API_URL + path)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return okHttp.newCall(request).await().parseJson()
    }

    companion object {
        const val STATUS_WATCHLIST = "watchlist"
        const val STATUS_WATCHING = "watching"
        const val STATUS_WATCHED = "watched"
        const val STATUS_NONE = "none"

        private const val PAGE_SIZE = 20
        private const val MAX_CAST = 10
        private const val MAX_SYNC_PAGES = 50

        /** Discovery lists by id: the TMDB path and whether it holds shows (`true`) or movies. */
        val DISCOVERY_CATEGORIES: Map<String, Pair<String, Boolean>> = linkedMapOf(
            "tmdb_trending_movies" to ("/trending/movie/week" to false),
            "tmdb_trending_tv" to ("/trending/tv/week" to true),
            "tmdb_popular_movies" to ("/movie/popular" to false),
            "tmdb_popular_tv" to ("/tv/popular" to true),
            "tmdb_top_rated_movies" to ("/movie/top_rated" to false),
            "tmdb_top_rated_tv" to ("/tv/top_rated" to true),
            "tmdb_now_playing" to ("/movie/now_playing" to false),
            "tmdb_airing_today" to ("/tv/airing_today" to true),
        )

        /** The `media_type` the tracking table stores, shared with the account statistics. */
        fun mediaTypeOf(isTv: Boolean) = if (isTv) "tv" else "movie"

        fun encodeId(tmdbId: Long, isTv: Boolean): Long = tmdbId * 2 + if (isTv) 1 else 0

        fun decodeId(id: Long): Pair<Long, Boolean> = (id shr 1) to (id and 1L == 1L)

        private fun path(isTv: Boolean) = if (isTv) "tv" else "movie"

        private fun webUrl(tmdbId: Long, isTv: Boolean) = "$BASE_WEB_URL/${path(isTv)}/$tmdbId"
    }
}
