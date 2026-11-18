package org.skepsun.kototoro.scrobbling.tmdb.data

import android.content.Context
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

    /** Needs a fresh request token, so the first read asks TMDB; the sign-in page opens with the result. */
    override val oauthUrl: String
        get() {
            check(apiKey.isNotBlank()) { "Missing TMDB API key" }
            val token = runBlocking(Dispatchers.IO) {
                get("/authentication/token/new").getString("request_token")
            }
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
        return response.getJSONArray("results").mapJSONNotNull { item ->
            val isTv = when (item.optString("media_type")) {
                "tv" -> true
                "movie" -> false
                else -> return@mapJSONNotNull null
            }
            val title = item.optString(if (isTv) "name" else "title").ifBlank { return@mapJSONNotNull null }
            val original = item.optString(if (isTv) "original_name" else "original_title")
            val date = item.optString(if (isTv) "first_air_date" else "release_date")
            val id = item.getLong("id")
            ScrobblerContent(
                id = encodeId(id, isTv),
                name = title,
                altName = original.takeIf { it.isNotBlank() && it != title },
                cover = item.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMAGE_URL + it },
                url = webUrl(id, isTv),
                mediaType = if (isTv) "tv" else "movie",
                subtitle = date.take(4).takeIf { it.isNotBlank() },
                score = item.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                scoreMax = MAX_RATING,
            )
        }
    }

    override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
        val (tmdbId, isTv) = decodeId(id)
        val details = get("/${path(isTv)}/$tmdbId", "append_to_response" to "credits")
        val title = details.optString(if (isTv) "name" else "title")
        val genres = details.optJSONArray("genres")?.mapJSONNotNull { it.optString("name").takeIf(String::isNotBlank) }
            .orEmpty()
        val cast = details.optJSONObject("credits")?.optJSONArray("cast")
            ?.mapJSONNotNull { it.optString("name").takeIf(String::isNotBlank) }.orEmpty().take(MAX_CAST)
        return ScrobblerContentInfo(
            id = id,
            name = title,
            cover = details.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMAGE_URL + it }.orEmpty(),
            url = webUrl(tmdbId, isTv),
            descriptionHtml = details.optString("overview"),
            contentType = ContentType.VIDEO,
            score = details.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            tags = genres,
            authors = cast,
            totalEpisodes = if (isTv) details.optInt("number_of_episodes").takeIf { it > 0 } else null,
        )
    }

    override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
        setWatchlist(content.id, true)
        saveRate(mangaId, content.id, STATUS_WATCHLIST, chapter = 0, comment = null, rating = 0f)
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
        if (rating > 0f) {
            val (tmdbId, isTv) = decodeId(entity.targetId)
            val value = (rating.coerceIn(0f, 1f) * MAX_RATING).coerceAtLeast(0.5f)
            post("/${path(isTv)}/$tmdbId/rating", JSONObject().put("value", (value * 2).toInt() / 2f))
        }
        saveRate(mangaId, entity.targetId, resolvedStatus, entity.chapter, comment ?: entity.comment, rating)
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

        fun encodeId(tmdbId: Long, isTv: Boolean): Long = tmdbId * 2 + if (isTv) 1 else 0

        fun decodeId(id: Long): Pair<Long, Boolean> = (id shr 1) to (id and 1L == 1L)

        private fun path(isTv: Boolean) = if (isTv) "tv" else "movie"

        private fun webUrl(tmdbId: Long, isTv: Boolean) = "$BASE_WEB_URL/${path(isTv)}/$tmdbId"
    }
}
