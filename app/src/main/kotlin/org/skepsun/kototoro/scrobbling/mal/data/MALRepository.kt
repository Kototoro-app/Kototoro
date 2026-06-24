package org.skepsun.kototoro.scrobbling.mal.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingByTargetAndMediaType
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingByTargetId
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserStats
import org.skepsun.kototoro.work.domain.WorkResolver
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private const val REDIRECT_URI = "kototoro://mal-auth"
private const val BASE_WEB_URL = "https://myanimelist.net"
private const val BASE_API_URL = "https://api.myanimelist.net/v2"
private const val ANIME_ENDPOINT = "anime"
private const val MANGA_ENDPOINT = "manga"

@Singleton
class MALRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.MAL) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MAL) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

	private val clientId = context.getString(R.string.mal_clientId)
	private val codeVerifier: String by lazy(::generateCodeVerifier)
	private val contentTypeHints = java.util.concurrent.ConcurrentHashMap<RemoteListKey, String>()
	private val contentPreviewCache = java.util.concurrent.ConcurrentHashMap<RemoteListKey, ScrobblerContentInfo>()

	override val oauthUrl: String
		get() = "$BASE_WEB_URL/v1/oauth2/authorize?" +
			"response_type=code" +
			"&client_id=$clientId" +
			"&redirect_uri=$REDIRECT_URI" +
			"&code_challenge=$codeVerifier" +
			"&code_challenge_method=plain"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		if (code != null) {
			body.add("client_id", clientId)
			body.add("grant_type", "authorization_code")
			body.add("code", code)
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code_verifier", codeVerifier)
		} else {
			val refreshToken = storage.refreshToken ?: return
			body.add("client_id", clientId)
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", refreshToken)
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${BASE_WEB_URL}/v1/oauth2/token")

		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		return loadUserProfile().user
	}

	override suspend fun loadUserProfile(): ScrobblerUserProfile {
		val request = Request.Builder()
			.get()
			.url("${BASE_API_URL}/users/@me?fields=anime_statistics,manga_statistics")
		val response = okHttp.newCall(request.build()).await().parseJson()
		val user = MALUser(response).also { storage.user = it }
		val animeStats = response.optJSONObject("anime_statistics")
		val mangaStats = response.optJSONObject("manga_statistics")
		return ScrobblerUserProfile(
			user = user,
			stats = ScrobblerUserStats(
				animeCount = animeStats.optIntOrNull("num_items_watching")
					?.plus(animeStats.optIntOrNull("num_items_completed") ?: 0)
					?.plus(animeStats.optIntOrNull("num_items_on_hold") ?: 0)
					?.plus(animeStats.optIntOrNull("num_items_dropped") ?: 0)
					?.plus(animeStats.optIntOrNull("num_items_plan_to_watch") ?: 0),
				mangaCount = mangaStats.optIntOrNull("num_items_reading")
					?.plus(mangaStats.optIntOrNull("num_items_completed") ?: 0)
					?.plus(mangaStats.optIntOrNull("num_items_on_hold") ?: 0)
					?.plus(mangaStats.optIntOrNull("num_items_dropped") ?: 0)
					?.plus(mangaStats.optIntOrNull("num_items_plan_to_read") ?: 0),
				episodesWatched = animeStats.optIntOrNull("num_episodes_watched"),
				chaptersRead = mangaStats.optIntOrNull("num_chapters_read"),
				animeMeanScore = animeStats.optDoubleOrNull("mean_score"),
				mangaMeanScore = mangaStats.optDoubleOrNull("mean_score"),
			),
		)
	}

	override suspend fun unregister(mangaId: Long) {
		return db.deleteScrobblingByWorkOrManga(ScrobblerService.MAL.id, mangaId, workResolver)
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val endpoint = mediaEndpoint(isAnime)
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(endpoint)
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", discoveryFields(endpoint))
			// WARNING! MAL API throws a 400 when the query is over 64 characters
			.addQueryParameter("q", query.take(64))
			.build()
		val request = Request.Builder().url(url).header("X-MAL-CLIENT-ID", clientId).get().build()
		val response = okHttp.newCall(request).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		val data = response.getJSONArray("data")
		return data.mapJSONNotNull { jsonToContent(it, query, endpoint) }
	}

	// ── Discovery API (public, uses X-MAL-CLIENT-ID) ─────────────

	/**
	 * Get anime ranking from MAL.
	 * @param rankingType "all", "airing", "upcoming", "tv", "ova", "movie", "special", "bypopularity", "favorite"
	 * @param limit max items per page
	 * @param offset pagination offset
	 */
	suspend fun getAnimeRanking(rankingType: String = "all", limit: Int = 20, offset: Int = 0): List<ScrobblerContent> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(ANIME_ENDPOINT)
			.addPathSegment("ranking")
			.addQueryParameter("ranking_type", rankingType)
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", discoveryFields(ANIME_ENDPOINT))
			.build()
		val request = Request.Builder().url(url)
			.header("X-MAL-CLIENT-ID", clientId)
			.get().build()
		return parseRankingList(okHttp.newCall(request).await().parseJson(), "anime")
	}

	/**
	 * Get seasonal anime from MAL.
	 * @param year e.g. 2026
	 * @param season "winter", "spring", "summer", "fall"
	 * @param sort "anime_score" or "anime_num_list_users"
	 */
	suspend fun getSeasonalAnime(year: Int, season: String, sort: String = "anime_num_list_users", limit: Int = 20, offset: Int = 0): List<ScrobblerContent> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(ANIME_ENDPOINT)
			.addPathSegment("season")
			.addPathSegment(year.toString())
			.addPathSegment(season)
			.addQueryParameter("sort", sort)
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", discoveryFields(ANIME_ENDPOINT))
			.build()
		val request = Request.Builder().url(url)
			.header("X-MAL-CLIENT-ID", clientId)
			.get().build()
		return parseRankingList(okHttp.newCall(request).await().parseJson(), "anime")
	}

	/**
	 * Get manga ranking from MAL.
	 */
	suspend fun getMangaRanking(rankingType: String = "all", limit: Int = 20, offset: Int = 0): List<ScrobblerContent> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(MANGA_ENDPOINT)
			.addPathSegment("ranking")
			.addQueryParameter("ranking_type", rankingType)
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", discoveryFields(MANGA_ENDPOINT))
			.build()
		val request = Request.Builder().url(url)
			.header("X-MAL-CLIENT-ID", clientId)
			.get().build()
		return parseRankingList(okHttp.newCall(request).await().parseJson(), "manga")
	}

	/**
	 * Search anime by text query.
	 */
	suspend fun searchAnime(query: String, offset: Int): List<ScrobblerContent> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(ANIME_ENDPOINT)
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", discoveryFields(ANIME_ENDPOINT))
			.addQueryParameter("q", query.take(64))
			.build()
		val request = Request.Builder().url(url)
			.header("X-MAL-CLIENT-ID", clientId)
			.get().build()
		val response = okHttp.newCall(request).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		return response.getJSONArray("data").mapJSONNotNull { jo ->
			createDiscoveryContent(jo.getJSONObject("node"), ANIME_ENDPOINT)
		}
	}

	private fun parseRankingList(json: JSONObject, mediaType: String): List<ScrobblerContent> {
		val data = json.optJSONArray("data") ?: return emptyList()
		return data.mapJSONNotNull { jo ->
			val node = jo.getJSONObject("node")
			createDiscoveryContent(node, mediaType)
		}
	}

	private fun JSONObject?.optIntOrNull(key: String): Int? {
		if (this == null || isNull(key)) {
			return null
		}
		return optInt(key)
	}

	private fun JSONObject?.optDoubleOrNull(key: String): Double? {
		if (this == null || isNull(key)) {
			return null
		}
		return optDouble(key)
	}

	private suspend fun isAnime(mangaId: Long): Boolean {
		val mangaItem = db.getMangaDao().find(mangaId) ?: return false
		if (mangaItem.manga.url.startsWith("file://") && (mangaItem.manga.url.contains("/video/") || arrayOf(".mp4", ".mkv", ".webm", ".ts", ".avi", ".m3u8").any { mangaItem.manga.url.endsWith(it, ignoreCase = true) })) {
			return true
		}
		val source = org.skepsun.kototoro.core.model.ContentSource(mangaItem.manga.source)
		val contentType = source.getContentType()
		return contentType == org.skepsun.kototoro.parsers.model.ContentType.VIDEO || contentType == org.skepsun.kototoro.parsers.model.ContentType.HENTAI_VIDEO
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val endpoint = db.getScrobblingDao()
			.findAllByTargetId(ScrobblerService.MAL.id, id)
			.preferredScrobblingByTargetAndMediaType()
			.values
			.firstNotNullOfOrNull { entity ->
				entity.mediaType.takeIf { it.isNotBlank() }
					?: entity.takeIf { it.mangaId != 0L }?.let { mediaEndpoint(isAnime(it.mangaId)) }
			}
			?: MANGA_ENDPOINT
		return getContentInfo(id, endpoint)
	}

	suspend fun getContentInfo(id: Long, mangaId: Long): ScrobblerContentInfo {
		return getContentInfo(id, mangaId, "")
	}

	suspend fun getContentInfo(id: Long, mangaId: Long, mediaType: String): ScrobblerContentInfo {
		return getContentInfo(id, resolvedEndpoint(id, mangaId, mediaType))
	}

	suspend fun getContentPreview(id: Long, mangaId: Long): ScrobblerContentInfo {
		return getContentPreview(id, mangaId, "")
	}

	suspend fun getContentPreview(id: Long, mangaId: Long, mediaType: String): ScrobblerContentInfo {
		return getContentPreview(id, resolvedEndpoint(id, mangaId, mediaType))
	}

	private suspend fun getContentInfo(id: Long, endpoint: String): ScrobblerContentInfo {
		val response = requestContentInfo(id, endpoint)
		if (response.has("id")) {
			rememberEndpoint(id, endpoint)
			rememberPreview(id, endpoint, ScrobblerContentInfo(response, endpoint))
			return buildContentInfo(response, endpoint)
		}
		val fallbackEndpoint = alternateEndpoint(endpoint)
		val fallbackResponse = requestContentInfo(id, fallbackEndpoint)
		check(fallbackResponse.has("id")) { "Invalid MAL content response for $id: \"$fallbackResponse\"" }
		rememberEndpoint(id, fallbackEndpoint)
		rememberPreview(id, fallbackEndpoint, ScrobblerContentInfo(fallbackResponse, fallbackEndpoint))
		return buildContentInfo(fallbackResponse, fallbackEndpoint)
	}

	private suspend fun getContentPreview(id: Long, endpoint: String): ScrobblerContentInfo {
		contentPreviewCache[RemoteListKey(endpoint, id)]?.let {
			return it
		}
		val response = requestContentInfo(id, endpoint)
		if (response.has("id")) {
			rememberEndpoint(id, endpoint)
			return ScrobblerContentInfo(response, endpoint).also {
				rememberPreview(id, endpoint, it)
			}
		}
		val fallbackEndpoint = alternateEndpoint(endpoint)
		val fallbackResponse = requestContentInfo(id, fallbackEndpoint)
		check(fallbackResponse.has("id")) { "Invalid MAL content response for $id: \"$fallbackResponse\"" }
		rememberEndpoint(id, fallbackEndpoint)
		return ScrobblerContentInfo(fallbackResponse, fallbackEndpoint).also {
			rememberPreview(id, fallbackEndpoint, it)
		}
	}

	/**
	 * Get anime details from MAL by anime ID.
	 * Uses the /anime/{id} endpoint instead of /manga/{id}.
	 */
	suspend fun getAnimeInfo(id: Long): ScrobblerContentInfo {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(ANIME_ENDPOINT)
			.addPathSegment(id.toString())
			.addQueryParameter("fields", "synopsis,genres,mean,num_episodes,rank,start_season,status,media_type")
			.build()
		val request = Request.Builder().url(url)
			.header("X-MAL-CLIENT-ID", clientId)
			.get().build()
		val response = okHttp.newCall(request).await().parseJson()
		return buildContentInfo(response, ANIME_ENDPOINT)
	}

	suspend fun getEntityInfo(
		entityType: EntityType,
		id: Long,
	): ScrobblerContentInfo? {
		return when (entityType) {
			EntityType.PERSON -> getPersonInfo(id)
			EntityType.CHARACTER -> getCharacterInfo(id)
			else -> null
		}
	}

	suspend fun searchEntities(
		entityType: EntityType,
		query: String,
		limit: Int = 10,
	): List<ScrobblerContent> {
		val categoryType = when (entityType) {
			EntityType.PERSON -> "person"
			EntityType.CHARACTER -> "character"
			else -> return emptyList()
		}
		val url = "$BASE_WEB_URL/search/prefix.json".toHttpUrl().newBuilder()
			.addQueryParameter("type", categoryType)
			.addQueryParameter("keyword", query)
			.build()
		val request = Request.Builder()
			.url(url)
			.get()
			.build()
		val categories = okHttp.newCall(request).await().parseJson().optJSONArray("categories") ?: return emptyList()
		for (i in 0 until categories.length()) {
			val category = categories.optJSONObject(i) ?: continue
			if (category.optString("type") != categoryType) {
				continue
			}
			return category.optJSONArray("items")?.mapJSONNotNull { item ->
				ScrobblerContent(
					id = item.getLong("id"),
					name = item.getStringOrNull("name") ?: return@mapJSONNotNull null,
					altName = item.optJSONObject("payload")?.getStringOrNull("alternative_name")?.takeIf { it.isNotBlank() },
					cover = item.getStringOrNull("image_url"),
					url = item.getStringOrNull("url") ?: "$BASE_WEB_URL/$categoryType/${item.getLong("id")}",
				)
			}.orEmpty().take(limit)
		}
		return emptyList()
	}

	private suspend fun buildContentInfo(json: JSONObject, mediaType: String): ScrobblerContentInfo {
		val baseInfo = ScrobblerContentInfo(json, mediaType)
		val supplemental = fetchSupplementalContent(baseInfo.url)
		return baseInfo.withSupplemental(supplemental)
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		content.mediaType?.let { rememberEndpoint(scrobblerContentId, it) }
		val endpoint = content.mediaType ?: resolvedEndpoint(scrobblerContentId, mangaId, "")
		val body = FormBody.Builder()
			.add("status", defaultStatus(endpoint == ANIME_ENDPOINT))
			.add("score", "0")
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(endpoint)
			.addPathSegment(scrobblerContentId.toString())
			.addPathSegment("my_list_status")
			.addQueryParameter("fields", "synopsis")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, scrobblerContentId, endpoint)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val endpoint = resolvedEndpoint(rateId.toLong(), mangaId, "")
		val body = FormBody.Builder()
			.add(progressField(endpoint), chapter.toString())
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(endpoint)
			.addPathSegment(rateId.toString())
			.addPathSegment("my_list_status")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, rateId.toLong(), endpoint)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val endpoint = resolvedEndpoint(rateId.toLong(), mangaId, "")
		val mappedStatus = mapStatusForEndpoint(status, endpoint)
		val body = FormBody.Builder()
			.add("status", mappedStatus.toString())
			.add("score", rating.toInt().toString())
			.add("comments", comment.orEmpty())
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(endpoint)
			.addPathSegment(rateId.toString())
			.addPathSegment("my_list_status")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, rateId.toLong(), endpoint)
	}

	/**
	 * Sync both anime and manga lists from MAL to local database.
	 * Uses MAL API:
	 * GET /v2/users/@me/animelist?fields=list_status&limit=1000
	 * GET /v2/users/@me/mangalist?fields=list_status&limit=1000
	 */
	suspend fun syncLibraryFromRemote(): Int {
		android.util.Log.d("MALRepo", "syncLibrary: start")
		val oldMappings = buildOldMappings()
		val synced = ArrayList<ScrobblingEntity>()
		synced += syncRemoteLibraryEntries(ANIME_ENDPOINT, oldMappings)
		synced += syncRemoteLibraryEntries(MANGA_ENDPOINT, oldMappings)

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.MAL.id)
			synced.forEach { entity ->
				db.upsertScrobbling(entity, workResolver)
			}
		}
		android.util.Log.d(
			"MALRepo",
			"syncLibrary: completed oldMappings=${oldMappings.size}, synced=${synced.size}",
		)
		return synced.size
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long, scrobblerContentId: Long, endpoint: String) {
		val statusJson = json.optJSONObject("my_list_status") ?: json
		rememberEndpoint(scrobblerContentId, endpoint)
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.MAL.id,
			id = scrobblerContentId.toInt(),
			mangaId = mangaId,
			targetId = scrobblerContentId,
			status = normalizeRemoteStatus(
				statusJson.optString("status", defaultStatus(endpoint == ANIME_ENDPOINT)),
				endpoint,
			),
			chapter = statusJson.optInt(progressField(endpoint), 0),
			comment = statusJson.optString("comments", ""),
			rating = (statusJson.optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
			mediaType = endpoint,
		)
		db.upsertScrobbling(entity, workResolver)
	}

	private suspend fun buildOldMappings(): Map<RemoteListKey, Long> {
		val existing = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.MAL.id)
			.preferredScrobblingByTargetId()
			.values
		val mappings = LinkedHashMap<RemoteListKey, Long>(existing.size)
		for (entity in existing) {
			val targetId = entity.targetId
			if (targetId == 0L) continue
			val endpoint = when {
				entity.mediaType.isNotBlank() -> entity.mediaType
				entity.mangaId == 0L -> null
				isAnime(entity.mangaId) -> ANIME_ENDPOINT
				else -> MANGA_ENDPOINT
			} ?: continue
			mappings.putIfAbsent(RemoteListKey(endpoint, targetId), entity.mangaId)
		}
		return mappings
	}

	private suspend fun syncRemoteLibraryEntries(
		endpoint: String,
		oldMappings: Map<RemoteListKey, Long>,
	): List<ScrobblingEntity> {
		val synced = ArrayList<ScrobblingEntity>()
		var page = 0
		var nextUrl: String? = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("users")
			.addPathSegment("@me")
			.addPathSegment("${endpoint}list")
			.addQueryParameter("fields", "main_picture,list_status{status,score,${progressField(endpoint)},comments}")
			.addQueryParameter("limit", "1000")
			.addQueryParameter("nsfw", "true")
			.build()
			.toString()

		while (nextUrl != null) {
			page += 1
			val request = Request.Builder().url(nextUrl).get().build()
			val callResponse = okHttp.newCall(request).await()
			if (!callResponse.isSuccessful) {
				android.util.Log.w("MALRepo", "syncLibrary($endpoint): HTTP ${callResponse.code} at $nextUrl")
				break
			}
			val response = callResponse.parseJson()
			val data = response.optJSONArray("data") ?: run {
				android.util.Log.w("MALRepo", "syncLibrary($endpoint): missing data key at $nextUrl")
				break
			}
			android.util.Log.d(
				"MALRepo",
				"syncLibrary($endpoint): page=$page, items=${data.length()}, accumulated=${synced.size}",
			)

			for (i in 0 until data.length()) {
				val entry = data.optJSONObject(i) ?: continue
				val node = entry.optJSONObject("node") ?: continue
				val listStatus = entry.optJSONObject("list_status") ?: continue
				val targetId = node.optLong("id", 0L)
				if (targetId == 0L) continue
				val rawStatus = listStatus.optString("status", "")
				val normalizedStatus = normalizeRemoteStatus(rawStatus, endpoint)
				synced.add(
					ScrobblingEntity(
						scrobbler = ScrobblerService.MAL.id,
						id = targetId.toInt(),
						mangaId = oldMappings[RemoteListKey(endpoint, targetId)] ?: 0L,
						targetId = targetId,
						status = normalizedStatus,
						chapter = listStatus.optInt(progressField(endpoint), 0),
						comment = listStatus.optString("comments", ""),
						rating = (listStatus.optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
						mediaType = endpoint,
						remoteTitle = node.getStringOrNull("title"),
						remoteCoverUrl = node.optJSONObject("main_picture")?.let {
							it.getStringOrNull("large") ?: it.getStringOrNull("medium")
						},
						remoteUrl = "$BASE_WEB_URL/$endpoint/$targetId",
					),
				)
				android.util.Log.d(
					"MALRepo",
					"syncLibrary($endpoint): targetId=$targetId, rawStatus=$rawStatus, normalizedStatus=$normalizedStatus, storedMediaType=$endpoint",
				)
			}

			nextUrl = response.optJSONObject("paging")?.getStringOrNull("next")
			android.util.Log.d(
				"MALRepo",
				"syncLibrary($endpoint): page=$page done, next=${!nextUrl.isNullOrBlank()}, accumulated=${synced.size}",
			)
		}

		return synced
	}

	private fun mediaEndpoint(isAnime: Boolean): String {
		return if (isAnime) ANIME_ENDPOINT else MANGA_ENDPOINT
	}

	private suspend fun resolvedEndpoint(targetId: Long, mangaId: Long, mediaType: String): String {
		if (mediaType.isNotBlank()) {
			return mediaType
		}
		cachedEndpoint(targetId)?.let { return it }
		val storedMediaTypes = db.getScrobblingDao()
			.findAllByTargetId(ScrobblerService.MAL.id, targetId)
			.mapNotNull { it.mediaType.takeIf(String::isNotBlank) }
			.distinct()
		if (storedMediaTypes.size == 1) {
			return storedMediaTypes.first()
		}
		return mediaEndpoint(isAnime(mangaId))
	}

	private fun rememberEndpoint(targetId: Long, endpoint: String) {
		contentTypeHints[RemoteListKey(endpoint, targetId)] = endpoint
	}

	private fun rememberPreview(targetId: Long, endpoint: String, info: ScrobblerContentInfo) {
		contentPreviewCache[RemoteListKey(endpoint, targetId)] = info
	}

	private fun cachedEndpoint(targetId: Long): String? {
		val endpoints = contentTypeHints.keys
			.asSequence()
			.filter { it.targetId == targetId }
			.map { it.endpoint }
			.distinct()
			.toList()
		return endpoints.singleOrNull()
	}

	private suspend fun requestContentInfo(id: Long, endpoint: String): JSONObject {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(endpoint)
			.addPathSegment(id.toString())
			.addQueryParameter("fields", detailFields(endpoint))
			.build()
		val request = Request.Builder().url(url)
		return okHttp.newCall(request.build()).await().parseJson()
	}

	private fun alternateEndpoint(endpoint: String): String {
		return if (endpoint == ANIME_ENDPOINT) MANGA_ENDPOINT else ANIME_ENDPOINT
	}

	private fun defaultStatus(isAnime: Boolean): String {
		return if (isAnime) "watching" else "reading"
	}

	private fun normalizeRemoteStatus(status: String?, endpoint: String): String? {
		if (endpoint != ANIME_ENDPOINT) return status
		return when (status) {
			"watching" -> "reading"
			"plan_to_watch" -> "plan_to_read"
			else -> status
		}
	}

	private fun mapStatusForEndpoint(status: String?, endpoint: String): String? {
		if (endpoint != ANIME_ENDPOINT) return status
		return when (status) {
			"reading" -> "watching"
			"plan_to_read" -> "plan_to_watch"
			else -> status
		}
	}

	private fun progressField(endpoint: String): String {
		return if (endpoint == ANIME_ENDPOINT) "num_watched_episodes" else "num_chapters_read"
	}

	private fun detailFields(endpoint: String): String {
		return if (endpoint == ANIME_ENDPOINT) {
			"synopsis,genres,mean,num_episodes,rank,start_season,status,media_type"
		} else {
			"synopsis,genres,mean,num_chapters,rank,start_date,status,media_type,authors"
		}
	}

	private suspend fun fetchSupplementalContent(contentUrl: String): MalSupplementalContent {
		return runCatching {
			val request = Request.Builder()
				.get()
				.url(contentUrl)
				.build()
			val html = okHttp.newCall(request).await().body?.string().orEmpty()
			if (html.isBlank()) {
				return@runCatching MalSupplementalContent()
			}
			val doc = Jsoup.parse(html, contentUrl)
			val reviews = parseMalReviews(doc)
			val comments = buildList {
				parseMalForumTopicStubs(doc).forEach { topic ->
					fetchMalForumThread(topic)?.let(::add)
				}
			}
			MalSupplementalContent(
				commentThreads = comments,
				reviews = reviews,
			)
		}.getOrDefault(MalSupplementalContent())
	}

	private suspend fun getCharacterInfo(id: Long): ScrobblerContentInfo {
		val url = "$BASE_WEB_URL/character/$id"
		val doc = fetchHtmlDocument(url)
		val detailsHeader = doc.selectFirst("h2.normal_header")
		val title = detailsHeader?.ownText()?.trim()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
		val nativeName = detailsHeader?.selectFirst("small")?.text()?.trim()
		val cover = doc.selectFirst("td[width=225] img")
			?.extractMalImageUrl(doc)
			.orEmpty()
		val description = detailsHeader
			?.collectHtmlUntilNextSectionHeader()
			.orEmpty()
		val infoProperties = parseCharacterInfoProperties(doc)
		val relatedAnime = parseCharacterMediaSection(doc, "Animeography")
		val relatedManga = parseCharacterMediaSection(doc, "Mangaography")
		val voiceActors = parseCharacterVoiceActors(doc)
		return ScrobblerContentInfo(
			id = id,
			name = title.ifBlank { nativeName ?: "Unknown" },
			cover = cover,
			url = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { url },
			descriptionHtml = description,
			authors = voiceActors.map { it.name },
			infoboxProperties = infoProperties,
			relatedWorks = relatedAnime + relatedManga,
			extraSections = listOfNotNull(
				relatedAnime.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Animeography",
						items = it,
					)
				},
				relatedManga.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Mangaography",
						items = it,
					)
				},
				voiceActors.takeIf { it.isNotEmpty() }?.let { actors ->
					ScrobblerContentInfo.RelatedSection(
						title = "Voice Actors",
						items = actors.mapIndexed { index, actor ->
							ScrobblerContentInfo.RelatedWork(
								id = actor.id ?: -(index + 1).toLong(),
								title = actor.name,
								coverUrl = actor.avatarUrl.orEmpty(),
								relationship = actor.url?.let { parseLastPathSegment(it) },
								url = actor.url.orEmpty(),
							)
						},
					)
				},
			),
		)
	}

	private suspend fun getPersonInfo(id: Long): ScrobblerContentInfo {
		val url = "$BASE_WEB_URL/people/$id"
		val doc = fetchHtmlDocument(url)
		val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { url }
		val title = doc.selectFirst("h1.title-name strong")?.text()?.trim()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
		val cover = doc.selectFirst("td[width=225] img")
			?.extractMalImageUrl(doc)
			.orEmpty()
		val infoProperties = parsePersonInfoProperties(doc)
		val moreHtml = doc.selectFirst(".people-informantion-more")?.html().orEmpty()
		val voiceRoles = parsePersonVoiceActingRoles(doc)
		val staffWorks = parsePeopleWorksTable(doc, "Anime Staff Positions", ".js-table-people-staff")
		val publishedManga = parsePeopleWorksTable(doc, "Published Manga")
		val voicedWorks = voiceRoles.map { it.work }.distinctBy { it.id.takeIf { idValue -> idValue > 0 } ?: it.url }
		val voicedCharacters = voiceRoles.map { role ->
			ScrobblerContentInfo.RelatedWork(
				id = role.character.id,
				title = role.character.title,
				coverUrl = role.character.coverUrl,
				relationship = listOfNotNull(
					role.work.title.takeIf { it.isNotBlank() },
					role.character.relationship,
				).joinToString(" · ").ifBlank { null },
				url = role.character.url,
			)
		}.distinctBy { it.id.takeIf { idValue -> idValue > 0 } ?: it.url }
		return ScrobblerContentInfo(
			id = id,
			name = title.ifBlank { "Unknown" },
			cover = cover,
			url = canonicalUrl,
			descriptionHtml = moreHtml,
			authors = voiceRoles.mapNotNull { role ->
				role.character.relationship?.takeIf { it.isNotBlank() }?.let { relation ->
					"${role.character.title} ($relation)"
				} ?: role.character.title.takeIf { it.isNotBlank() }
			}.distinct(),
			infoboxProperties = infoProperties,
			extraSections = listOfNotNull(
				voicedWorks.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Voiced Works",
						items = it.map { work ->
							val voiceRole = voiceRoles.firstOrNull { role -> role.work.id == work.id && role.work.url == work.url }
							work.copy(
								relationship = listOfNotNull(
									voiceRole?.character?.title?.takeIf { characterName -> characterName.isNotBlank() },
									voiceRole?.character?.relationship,
								).joinToString(" · ").ifBlank { work.relationship },
							)
						},
					)
				},
				voicedCharacters.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Voiced Characters",
						items = it,
					)
				},
				staffWorks.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Anime Staff Positions",
						items = it,
					)
				},
				publishedManga.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Published Manga",
						items = it,
					)
				},
			),
		)
	}

	private suspend fun fetchHtmlDocument(url: String): Document {
		val request = Request.Builder()
			.get()
			.url(url)
			.build()
		val html = okHttp.newCall(request).await().body?.string().orEmpty()
		return Jsoup.parse(html, url)
	}

	private fun parseCharacterInfoProperties(doc: Document): List<Pair<String, String>> {
		val header = doc.selectFirst("h2.normal_header") ?: return emptyList()
		return buildList {
			header.collectHtmlUntilNextSectionHeader()
				.split("<br />", "<br>", "<br/>")
				.map { Jsoup.parseBodyFragment(it).text().trim() }
				.forEach { line ->
					val key = line.substringBefore(':').trim()
					val value = line.substringAfter(':', "").trim()
					if (key.isNotBlank() && value.isNotBlank()) {
						add(key to value)
					}
				}
		}.distinct()
	}

	private fun parsePersonInfoProperties(doc: Document): List<Pair<String, String>> {
		val root = doc.selectFirst("#profileRows")?.parent() ?: return emptyList()
		return buildList {
			root.select("span.dark_text").forEach { label ->
				val key = label.text().removeSuffix(":").trim()
				val container = label.parent()
				val value = container?.text()
					?.substringAfter(label.text(), "")
					?.trim()
					?.takeIf { it.isNotBlank() }
					?: label.nextSibling()?.outerHtml()?.let { Jsoup.parse(it).text().trim() }
				if (key.isNotBlank() && !value.isNullOrBlank()) {
					add(key to value)
				}
			}
			doc.selectFirst(".people-informantion-more")
				?.html()
				?.split("<br />", "<br>")
				?.map { Jsoup.parse(it).text().trim() }
				?.forEach { line ->
					val key = line.substringBefore(':').trim()
					val value = line.substringAfter(':', "").trim()
					if (key.isNotBlank() && value.isNotBlank()) {
						add(key to value)
					}
				}
		}.distinct()
	}

	private fun parseCharacterMediaSection(
		doc: Document,
		headerTitle: String,
	): List<ScrobblerContentInfo.RelatedWork> {
		val header = doc.select(".normal_header").firstOrNull { it.text().trim() == headerTitle } ?: return emptyList()
		val table = header.nextElementSibling()?.takeIf { it.tagName() == "table" } ?: return emptyList()
		return table.select("tr").mapNotNull { row ->
			val titleLink = row.selectFirst("td:nth-child(2) > a[href*=/anime/], td:nth-child(2) > a[href*=/manga/]")
				?: return@mapNotNull null
			val url = titleLink.absUrl("href").ifBlank { titleLink.attr("href") }
			val id = parseMalNumericId(url)
			ScrobblerContentInfo.RelatedWork(
				id = id,
				title = titleLink.text().trim(),
				coverUrl = row.selectFirst("td:first-child img")?.extractMalImageUrl(doc).orEmpty(),
				relationship = row.selectFirst("small")?.text()?.trim()?.takeIf { it.isNotBlank() },
				url = url,
			)
		}
	}

	private fun parseCharacterVoiceActors(doc: Document): List<ScrobblerContentInfo.PersonInfo> {
		val header = doc.select(".normal_header").firstOrNull { it.text().trim() == "Voice Actors" } ?: return emptyList()
		return header.parent()
			?.children()
			?.dropWhile { it !== header }
			?.drop(1)
			?.takeWhile { element -> !(element.hasClass("normal_header") && element.text().isNotBlank()) }
			?.filter { it.tagName() == "table" }
			?.mapNotNull { table ->
				val link = table.selectFirst("a[href*=/people/]") ?: return@mapNotNull null
				val url = link.absUrl("href").ifBlank { link.attr("href") }
				ScrobblerContentInfo.PersonInfo(
					id = parseMalNumericId(url).takeIf { it > 0 },
					name = link.text().trim(),
					avatarUrl = table.selectFirst("img")?.extractMalImageUrl(doc),
					url = url,
				)
			}
			.orEmpty()
	}

	private fun parsePersonVoiceActingRoles(doc: Document): List<MalVoiceActingRole> {
		val table = findSectionTable(doc, "Voice Acting Roles", ".js-table-people-character") ?: return emptyList()
		return table.select("tr.js-people-character").mapNotNull { row ->
			val workLink = row.selectFirst("a.js-people-title, td:nth-child(2) a[href*=/anime/], td:nth-child(2) a[href*=/manga/]")
				?: return@mapNotNull null
			val workUrl = workLink.absUrl("href").ifBlank { workLink.attr("href") }
			val characterLink = row.selectFirst("td:nth-child(3) a[href*=/character/]") ?: return@mapNotNull null
			val characterUrl = characterLink.absUrl("href").ifBlank { characterLink.attr("href") }
			val work = ScrobblerContentInfo.RelatedWork(
				id = parseMalNumericId(workUrl),
				title = workLink.text().trim(),
				coverUrl = row.selectFirst("td:first-child img")?.extractMalImageUrl(doc).orEmpty(),
				relationship = buildString {
					append(characterLink.text().trim())
					row.select("td:nth-child(3) .spaceit_pad").getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }?.let {
						append(" · ")
						append(it)
					}
				}.ifBlank { null },
				url = workUrl,
			)
			val character = ScrobblerContentInfo.RelatedWork(
				id = parseMalNumericId(characterUrl),
				title = characterLink.text().trim(),
				coverUrl = row.selectFirst("td:nth-child(4) img")?.extractMalImageUrl(doc).orEmpty(),
				relationship = row.select("td:nth-child(3) .spaceit_pad").getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() },
				url = characterUrl,
			)
			MalVoiceActingRole(
				work = work,
				character = character,
			)
		}
	}

	private fun parsePeopleWorksTable(
		doc: Document,
		headerTitle: String,
		tableSelector: String? = null,
	): List<ScrobblerContentInfo.RelatedWork> {
		val table = findSectionTable(doc, headerTitle, tableSelector) ?: return emptyList()
		return table.select("tr").mapNotNull { row ->
			val titleLink = row.selectFirst("a.js-people-title, td:nth-child(2) a[href*=/anime/], td:nth-child(2) a[href*=/manga/]")
				?: return@mapNotNull null
			val url = titleLink.absUrl("href").ifBlank { titleLink.attr("href") }
			ScrobblerContentInfo.RelatedWork(
				id = parseMalNumericId(url),
				title = titleLink.text().trim(),
				coverUrl = row.selectFirst("td:first-child img")?.extractMalImageUrl(doc).orEmpty(),
				relationship = row.selectFirst("td:nth-child(2) small")?.text()?.trim()?.takeIf { it.isNotBlank() },
				url = url,
			)
		}
	}

	private fun findSectionTable(
		doc: Document,
		headerTitle: String,
		tableSelector: String? = null,
	): Element? {
		val header = doc.select(".normal_header").firstOrNull {
			it.text().contains(headerTitle, ignoreCase = true)
		} ?: return null
		var sibling = header.nextElementSibling()
		while (sibling != null) {
			if (sibling.hasClass("normal_header")) {
				return null
			}
			if (sibling.tagName() == "table" && (tableSelector == null || sibling.`is`(tableSelector))) {
				return sibling
			}
			sibling = sibling.nextElementSibling()
		}
		return null
	}

	private fun Element.extractMalImageUrl(doc: Document): String? {
		return sequenceOf(
			absUrl("data-src"),
			absUrl("src"),
			absUrl("data-srcset").substringBefore(' ').trim(),
			absUrl("srcset").substringBefore(' ').trim(),
			attr("data-src"),
			attr("src"),
			attr("data-srcset").substringBefore(' ').trim(),
			attr("srcset").substringBefore(' ').trim(),
		).firstOrNull { it.isNotBlank() }
			?.let { raw ->
				when {
					raw.startsWith("//") -> "https:$raw"
					raw.startsWith("/") -> "${doc.location().toHttpUrl().scheme}://${doc.location().toHttpUrl().host}$raw"
					else -> raw
				}
			}
			?.takeUnless { it.contains("questionmark_", ignoreCase = true) }
	}

	private fun Element.collectHtmlUntilNextSectionHeader(): String {
		val html = StringBuilder()
		var sibling: Node? = nextSibling()
		while (sibling != null) {
			val element = sibling as? Element
			if (element?.hasClass("normal_header") == true) {
				break
			}
			html.append(sibling.outerHtml())
			sibling = sibling.nextSibling()
		}
		return html.toString().trim()
	}

	private fun parseMalNumericId(url: String): Long {
		return url.trimEnd('/')
			.substringAfterLast('/')
			.toLongOrNull()
			?: url.substringAfter("/anime/", "")
				.substringBefore('/')
				.toLongOrNull()
			?: url.substringAfter("/manga/", "")
				.substringBefore('/')
				.toLongOrNull()
			?: url.substringAfter("/people/", "")
				.substringBefore('/')
				.toLongOrNull()
			?: url.substringAfter("/character/", "")
				.substringBefore('/')
				.toLongOrNull()
			?: 0L
	}

	private fun parseLastPathSegment(url: String): String? {
		return url.trimEnd('/')
			.substringAfterLast('/')
			.replace('_', ' ')
			.takeIf { it.isNotBlank() }
	}

	private fun parseMalReviews(doc: org.jsoup.nodes.Document): List<ScrobblerContentInfo.ReviewEntry> {
		return doc.select(".review-element.js-review-element")
			.take(5)
			.mapNotNull { element ->
				val authorLink = element.selectFirst(".username a") ?: return@mapNotNull null
				val reviewLink = element.selectFirst(".bottom-navi .open a") ?: return@mapNotNull null
				val reviewUrl = reviewLink.absUrl("href").ifBlank { reviewLink.attr("href") }
				if (reviewUrl.isBlank()) {
					return@mapNotNull null
				}
				val textBlock = element.selectFirst(".text")?.clone() ?: return@mapNotNull null
				textBlock.select(".js-visible").remove()
				val excerpt = textBlock.html().htmlToPlainText()
				if (excerpt.isBlank()) {
					return@mapNotNull null
				}
				val tagTitle = element.select(".tags .tag")
					.eachText()
					.map(String::trim)
					.filter(String::isNotBlank)
					.joinToString(" · ")
				val reviewerScore = element.selectFirst(".rating .num")?.text()?.trim()
				val title = listOfNotNull(
					tagTitle.takeIf { it.isNotBlank() },
					reviewerScore?.takeIf { it.isNotBlank() }?.let { "$it/10" },
				).joinToString(" · ").ifBlank { "Review" }
				ScrobblerContentInfo.ReviewEntry(
					id = reviewUrl.substringAfter("id=").substringBefore('&').ifBlank { reviewUrl.hashCode().toString() },
					title = title,
					authorName = authorLink.text().trim(),
					authorUrl = authorLink.absUrl("href").ifBlank { authorLink.attr("href") },
					avatarUrl = element.selectFirst(".thumb img")?.let { image ->
						image.attr("data-src").ifBlank { image.attr("src") }
					}?.takeIf { it.isNotBlank() },
					postedAt = element.selectFirst(".update_at")?.text()?.trim(),
					excerpt = excerpt.truncateForDetailExcerpt(),
					url = reviewUrl,
				)
			}
	}

	private fun parseMalForumTopicStubs(doc: org.jsoup.nodes.Document): List<MalForumTopicStub> {
		return doc.select("#forumTopics tr[data-topic-id]")
			.take(3)
			.mapNotNull { row ->
				val titleCell = row.selectFirst("td.forum_boardrow1 a[href*=/forum/?topicid=]") ?: return@mapNotNull null
				val topicId = row.attr("data-topic-id").toLongOrNull() ?: return@mapNotNull null
				val repliesText = row.select("td").getOrNull(2)?.text()?.substringBefore(" repl")?.trim()
				MalForumTopicStub(
					id = topicId,
					title = titleCell.text().trim(),
					url = titleCell.absUrl("href").ifBlank { "$BASE_WEB_URL${titleCell.attr("href")}" },
					replyCount = repliesText?.replace(",", "")?.toIntOrNull(),
				)
			}
	}

	private suspend fun fetchMalForumThread(
		topic: MalForumTopicStub,
	): ScrobblerContentInfo.CommentThread? {
		return runCatching {
			val request = Request.Builder()
				.get()
				.url(topic.url)
				.build()
			val html = okHttp.newCall(request).await().body?.string().orEmpty()
			if (html.isBlank()) {
				return@runCatching null
			}
			val doc = Jsoup.parse(html, topic.url)
			val messages = doc.select(".forum-topic-message.message[id]")
				.filter { it.id().startsWith("msg") }
			val firstMessage = messages.firstOrNull() ?: return@runCatching null
			val content = firstMessage.extractMalForumMessageText()
			if (content.isBlank()) {
				return@runCatching null
			}
			val firstUserLink = firstMessage.selectFirst(".profile .username a")
			ScrobblerContentInfo.CommentThread(
				id = "topic_${topic.id}",
				userName = firstUserLink?.text()?.trim().orEmpty().ifBlank { "MAL User" },
				userUrl = firstUserLink?.absUrl("href")?.ifBlank { firstUserLink.attr("href") },
				avatarUrl = firstMessage.selectFirst(".profile .forum-icon img")?.let { image ->
					image.attr("data-src").ifBlank { image.attr("src") }
				}?.takeIf { it.isNotBlank() },
				status = buildString {
					append(topic.title)
					topic.replyCount?.let {
						append(" · ")
						append(it)
						append(" replies")
					}
				},
				postedAt = firstMessage.selectFirst(".message-header .date")?.text()?.trim(),
				content = content,
				replies = messages.drop(1).take(3).mapNotNull { reply ->
					val replyUser = reply.selectFirst(".profile .username a")
					val replyContent = reply.extractMalForumMessageText()
					if (replyContent.isBlank()) {
						return@mapNotNull null
					}
					ScrobblerContentInfo.CommentReply(
						id = reply.id().removePrefix("msg").ifBlank { "${topic.id}_${reply.hashCode()}" },
						userName = replyUser?.text()?.trim().orEmpty().ifBlank { "MAL User" },
						userUrl = replyUser?.absUrl("href")?.ifBlank { replyUser.attr("href") },
						avatarUrl = reply.selectFirst(".profile .forum-icon img")?.let { image ->
							image.attr("data-src").ifBlank { image.attr("src") }
						}?.takeIf { it.isNotBlank() },
						postedAt = reply.selectFirst(".message-header .date")?.text()?.trim(),
						content = replyContent,
					)
				},
			)
		}.getOrNull()
	}

	private fun org.jsoup.nodes.Element.extractMalForumMessageText(): String {
		val contentCell = selectFirst(".content table.body td")?.clone() ?: return ""
		contentCell.select("input, script, style").remove()
		return contentCell.html().htmlToPlainText().truncateForDetailExcerpt(maxLength = 1200)
	}

	override fun logout() {
		storage.clear()
	}

	private fun jsonToContent(json: JSONObject, sourceTitle: String, mediaType: String): ScrobblerContent {
		val node = json.getJSONObject("node")
		val content = createDiscoveryContent(node, mediaType)
		return content.copy(
			isBestMatch = sequenceOf(content.name, content.primaryTitle, content.secondaryTitle, content.altName)
				.filterNotNull()
				.any { it.equals(sourceTitle, ignoreCase = true) },
		)
	}

	private fun createDiscoveryContent(node: JSONObject, mediaType: String): ScrobblerContent {
		val title = node.getString("title")
		val id = node.getLong("id")
		rememberEndpoint(id, mediaType)
		val alternativeTitles = node.optJSONObject("alternative_titles")
		val originalTitle = alternativeTitles?.getStringOrNull("ja")
		val secondaryTitle = sequenceOf(
			title,
			alternativeTitles?.getStringOrNull("en"),
		).filterNotNull().firstOrNull { !it.equals(originalTitle, ignoreCase = true) }
		val subtitle = listOfNotNull(
			node.getStringOrNull("status")?.replace("_", " "),
			buildMalStartLabel(node, mediaType),
		).joinToString(" · ").ifBlank { null }
		val score = node.optDouble("mean").takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
		return ScrobblerContent(
			id = id,
			name = title,
			altName = originalTitle,
			cover = node.optJSONObject("main_picture")?.getStringOrNull("large"),
			url = "$BASE_WEB_URL/$mediaType/$id",
			mediaType = mediaType,
			primaryTitle = originalTitle ?: title,
			secondaryTitle = secondaryTitle,
			subtitle = subtitle,
			progressText = buildMalProgressText(node, mediaType),
			score = score,
			scoreMax = 10f,
		)
	}

	private fun discoveryFields(endpoint: String): String {
		return if (endpoint == ANIME_ENDPOINT) {
			"alternative_titles,mean,num_episodes,status,start_season"
		} else {
			"alternative_titles,mean,num_chapters,status,start_date"
		}
	}

	private fun buildMalProgressText(node: JSONObject, mediaType: String): String? {
		return if (mediaType == ANIME_ENDPOINT) {
			node.optInt("num_episodes").takeIf { it > 0 }?.let { "EP $it" }
		} else {
			node.optInt("num_chapters").takeIf { it > 0 }?.let { "CH $it" }
		}
	}

	private fun buildMalStartLabel(node: JSONObject, mediaType: String): String? {
		return if (mediaType == ANIME_ENDPOINT) {
			node.optJSONObject("start_season")?.let { season ->
				val year = season.optInt("year").takeIf { it > 0 } ?: return@let null
				val name = season.getStringOrNull("season")?.replaceFirstChar { it.uppercaseChar() } ?: return@let year.toString()
				"$name $year"
			}
		} else {
			node.getStringOrNull("start_date")?.takeIf { it.isNotBlank() }?.take(10)
		}
	}

	private fun syntheticEpisodes(
		total: Int?,
		label: String,
		url: String,
	): List<ScrobblerContentInfo.EpisodeInfo> {
		val count = total?.takeIf { it > 0 } ?: return emptyList()
		return (1..count).map { number ->
			ScrobblerContentInfo.EpisodeInfo(
				number = number.toString(),
				title = "$label $number",
				url = url,
			)
		}
	}

	private fun ScrobblerContentInfo(json: JSONObject, mediaType: String): ScrobblerContentInfo {
		val tags = json.optJSONArray("genres")?.mapJSONNotNull {
			it.getStringOrNull("name")
		}.orEmpty()
		val authors = json.optJSONArray("authors")?.mapJSONNotNull { edge ->
			val node = edge.optJSONObject("node") ?: return@mapJSONNotNull null
			listOfNotNull(
				node.getStringOrNull("first_name")?.takeIf { it.isNotBlank() },
				node.getStringOrNull("last_name")?.takeIf { it.isNotBlank() },
			).joinToString(" ").ifBlank { null }
		}.orEmpty()
		val staff = json.optJSONArray("authors")?.mapJSONNotNull { edge ->
			val node = edge.optJSONObject("node") ?: return@mapJSONNotNull null
			val name = listOfNotNull(
				node.getStringOrNull("first_name")?.takeIf { it.isNotBlank() },
				node.getStringOrNull("last_name")?.takeIf { it.isNotBlank() },
			).joinToString(" ").ifBlank { return@mapJSONNotNull null }
			val id = node.optLong("id", 0L).takeIf { it > 0L }
			ScrobblerContentInfo.PersonInfo(
				id = id,
				name = name,
				url = id?.let { "$BASE_WEB_URL/people/$it" },
				role = "Author",
			)
		}.orEmpty()
		val totalCount = if (mediaType == ANIME_ENDPOINT) {
			json.optInt("num_episodes")
		} else {
			json.optInt("num_chapters")
		}.takeIf { it > 0 }
		val infobox = buildList {
			json.optDouble("mean").takeIf { !it.isNaN() && it > 0.0 }?.let {
				add("Score" to it.toString())
			}
			json.optInt("rank").takeIf { it > 0 }?.let {
				add("Rank" to "#$it")
			}
			json.getStringOrNull("status")?.takeIf { it.isNotBlank() }?.let {
				add("Status" to it)
			}
			json.getStringOrNull("media_type")?.takeIf { it.isNotBlank() }?.let {
				add("Type" to it)
			}
			if (mediaType == ANIME_ENDPOINT) {
				json.optInt("num_episodes").takeIf { it > 0 }?.let {
					add("Episodes" to it.toString())
				}
				json.optJSONObject("start_season")?.let { season ->
					val year = season.optInt("year", 0)
					val value = listOfNotNull(
						season.getStringOrNull("season"),
						year.takeIf { it > 0 }?.toString(),
					).joinToString(" ")
					if (value.isNotBlank()) {
						add("Season" to value)
					}
				}
			} else {
				json.optInt("num_chapters").takeIf { it > 0 }?.let {
					add("Chapters" to it.toString())
				}
				json.getStringOrNull("start_date")?.takeIf { it.isNotBlank() }?.let {
					add("Start date" to it)
				}
			}
		}
		return ScrobblerContentInfo(
			id = json.getLong("id"),
			name = json.getString("title"),
			cover = json.optJSONObject("main_picture")?.getStringOrNull("large") ?: "",
			url = "$BASE_WEB_URL/$mediaType/${json.getLong("id")}",
			descriptionHtml = json.optString("synopsis", ""),
			contentType = if (mediaType == ANIME_ENDPOINT) {
				ContentType.VIDEO
			} else {
				ContentType.MANGA
			},
			tags = tags,
			authors = authors,
			staff = staff,
			totalEpisodes = totalCount,
			infoboxProperties = infobox,
			episodes = syntheticEpisodes(
				total = totalCount,
				label = if (mediaType == ANIME_ENDPOINT) "Episode" else "Chapter",
				url = "$BASE_WEB_URL/$mediaType/${json.getLong("id")}",
			),
		)
	}

	private fun ScrobblerContentInfo.withSupplemental(
		supplemental: MalSupplementalContent,
	): ScrobblerContentInfo {
		return ScrobblerContentInfo(
			id = id,
			name = name,
			cover = cover,
			url = url,
			descriptionHtml = descriptionHtml,
			contentType = contentType,
			score = score,
			rank = rank,
			tags = tags,
			authors = authors,
			staff = staff,
			totalEpisodes = totalEpisodes,
			infoboxProperties = infoboxProperties,
			episodes = episodes,
			characters = characters,
			commentThreads = supplemental.commentThreads,
			reviews = supplemental.reviews,
			relatedWorks = relatedWorks,
			recommendations = recommendations,
			extraSections = extraSections,
			actions = actions,
		)
	}

	private fun String.htmlToPlainText(): String {
		return Jsoup.parseBodyFragment(this)
			.text()
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun String.truncateForDetailExcerpt(maxLength: Int = 900): String {
		if (length <= maxLength) {
			return this
		}
		return take(maxLength).trimEnd() + "…"
	}

	@Suppress("FunctionName")
	private fun MALUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getStringOrNull("picture"),
		service = ScrobblerService.MAL,
	)

	private fun generateCodeVerifier(): String {
		val codeVerifier = ByteArray(50)
		SecureRandom().nextBytes(codeVerifier)
		return Base64.encodeToString(codeVerifier, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
	}

	private data class RemoteListKey(
		val endpoint: String,
		val targetId: Long,
	)

	private data class MalSupplementalContent(
		val commentThreads: List<ScrobblerContentInfo.CommentThread> = emptyList(),
		val reviews: List<ScrobblerContentInfo.ReviewEntry> = emptyList(),
	)

	private data class MalForumTopicStub(
		val id: Long,
		val title: String,
		val url: String,
		val replyCount: Int?,
	)

	private data class MalVoiceActingRole(
		val work: ScrobblerContentInfo.RelatedWork,
		val character: ScrobblerContentInfo.RelatedWork,
	)
}
