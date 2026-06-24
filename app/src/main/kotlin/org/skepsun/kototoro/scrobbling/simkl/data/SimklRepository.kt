package org.skepsun.kototoro.scrobbling.simkl.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingByTargetId
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingForManga
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserStats
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val REDIRECT_URI = "kototoro://simkl-auth"
private const val BASE_WEB_URL = "https://simkl.com"
private const val BASE_API_URL = "https://api.simkl.com"
private const val IMAGE_PROXY_URL = "https://wsrv.nl/?url=https://simkl.in"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private const val SEARCH_PAGE_SIZE = 10
private const val DEFAULT_WATCHLIST_STATUS = "plantowatch"
private const val WATCHED_MOVIE_PROGRESS = 1
private const val KEY_ACTIVITY_ALL = "sync_activity_all"

private enum class SimklEndpoint(
	val detailPath: String,
	private val searchPath: String,
	val webPath: String,
	val contentType: ContentType,
) {
	ANIME(
		detailPath = "anime",
		searchPath = "anime",
		webPath = "anime",
		contentType = ContentType.VIDEO,
	),
	TV(
		detailPath = "tv",
		searchPath = "tv",
		webPath = "tv",
		contentType = ContentType.VIDEO,
	),
	MOVIES(
		detailPath = "movies",
		searchPath = "movie",
		webPath = "movies",
		contentType = ContentType.VIDEO,
	),
	;

	fun buildSearchUrl(clientId: String, query: String, page: Int, limit: Int): String {
		return BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("search")
			.addPathSegment(searchPath)
			.addQueryParameter("q", query)
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("extended", "full")
			.addQueryParameter("client_id", clientId)
			.build()
			.toString()
	}

	fun buildDetailsUrl(clientId: String, id: Long): String {
		return BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(detailPath)
			.addPathSegment(id.toString())
			.addQueryParameter("extended", "full")
			.addQueryParameter("client_id", clientId)
			.build()
			.toString()
	}

	fun buildEpisodesUrl(clientId: String, id: Long): String {
		return BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(detailPath)
			.addPathSegment("episodes")
			.addPathSegment(id.toString())
			.addQueryParameter("client_id", clientId)
			.build()
			.toString()
	}

	fun buildWebUrl(remoteId: Long, slug: String?): String {
		return buildString {
			append(BASE_WEB_URL)
			append("/")
			append(webPath)
			append("/")
			append(remoteId)
			if (!slug.isNullOrBlank()) {
				append("/")
				append(slug)
			}
		}
	}

	companion object {
		fun fromCategoryId(id: String): SimklEndpoint? = when {
			id.startsWith("simkl_anime_") -> ANIME
			id.startsWith("simkl_tv_") -> TV
			id.startsWith("simkl_movies_") -> MOVIES
			else -> null
		}

		fun fromUrl(url: String?): SimklEndpoint? = when {
			url.isNullOrBlank() -> null
			"/anime/" in url -> ANIME
			"/tv/" in url -> TV
			"/movies/" in url -> MOVIES
			else -> null
		}

		fun fromApiValue(value: String?): SimklEndpoint? = when (value?.lowercase()) {
			"anime" -> ANIME
			"tv", "show", "shows" -> TV
			"movie", "movies" -> MOVIES
			else -> null
		}
	}
}

private enum class SimklSyncType(
	val apiType: String,
	val responseKey: String,
	val activityKey: String,
	val endpoint: SimklEndpoint,
) {
	ANIME(
		apiType = "anime",
		responseKey = "anime",
		activityKey = "anime",
		endpoint = SimklEndpoint.ANIME,
	),
	SHOWS(
		apiType = "shows",
		responseKey = "shows",
		activityKey = "tv_shows",
		endpoint = SimklEndpoint.TV,
	),
	MOVIES(
		apiType = "movies",
		responseKey = "movies",
		activityKey = "movies",
		endpoint = SimklEndpoint.MOVIES,
	),
	;
}

private data class SimklActivityGroup(
	val all: String?,
	val ratedAt: String?,
	val removedFromList: String?,
)

private data class SimklActivitySnapshot(
	val all: String?,
	val groups: Map<SimklSyncType, SimklActivityGroup>,
)

data class SimklCatalogItem(
	val remoteId: Long,
	val title: String,
	val altTitle: String?,
	val coverUrl: String?,
	val subtitle: String?,
	val score: Float?,
	val url: String?,
)

private enum class SimklDiscoveryCategory(
	val id: String,
	private val endpointType: SimklEndpoint,
	private val apiPath: String,
	val supportsPaging: Boolean,
) {
	ANIME_PREMIERES(
		id = "simkl_anime_premieres",
		endpointType = SimklEndpoint.ANIME,
		apiPath = "/anime/premieres/param?type=all",
		supportsPaging = false,
	),
	ANIME_AIRING(
		id = "simkl_anime_airing",
		endpointType = SimklEndpoint.ANIME,
		apiPath = "/anime/airing",
		supportsPaging = false,
	),
	ANIME_TRENDING(
		id = "simkl_anime_trending",
		endpointType = SimklEndpoint.ANIME,
		apiPath = "/anime/trending/today?extended=overview,metadata,tmdb,genres,trailer",
		supportsPaging = false,
	),
	ANIME_POPULAR(
		id = "simkl_anime_popular",
		endpointType = SimklEndpoint.ANIME,
		apiPath = "/anime/genres/all/all-types/all-countries/all-years",
		supportsPaging = true,
	),
	TV_PREMIERES(
		id = "simkl_tv_premieres",
		endpointType = SimklEndpoint.TV,
		apiPath = "/tv/premieres/param?type=all",
		supportsPaging = false,
	),
	TV_AIRING(
		id = "simkl_tv_airing",
		endpointType = SimklEndpoint.TV,
		apiPath = "/tv/airing",
		supportsPaging = false,
	),
	MOVIES_TRENDING(
		id = "simkl_movies_trending",
		endpointType = SimklEndpoint.MOVIES,
		apiPath = "/movies/trending/today?extended=overview,metadata,tmdb,genres,trailer",
		supportsPaging = false,
	),
	MOVIES_POPULAR(
		id = "simkl_movies_popular",
		endpointType = SimklEndpoint.MOVIES,
		apiPath = "/movies/genres/all/all-types/all-countries/all-years",
		supportsPaging = true,
	),
	;

	fun buildUrl(clientId: String, page: Int, date: String? = null): String {
		return "$BASE_API_URL$apiPath".toHttpUrl().newBuilder()
			.apply {
				addQueryParameter("client_id", clientId)
				if (supportsPaging) {
					addQueryParameter("page", (page + 1).toString())
				}
				if (this@SimklDiscoveryCategory == ANIME_AIRING || this@SimklDiscoveryCategory == TV_AIRING) {
					addQueryParameter("date", date ?: LocalDate.now().toString())
					addQueryParameter("sort", "time")
				}
			}
			.build()
			.toString()
	}

	fun buildWebUrl(remoteId: Long, slug: String?): String {
		return endpointType.buildWebUrl(remoteId, slug)
	}

	fun endpointType(): SimklEndpoint = endpointType

	companion object {
		fun fromId(id: String): SimklDiscoveryCategory? = entries.firstOrNull { it.id == id }
	}
}

@Singleton
class SimklRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.SIMKL) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.SIMKL) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

	private val clientId = context.getString(R.string.simkl_clientId)
	private val clientSecret = context.getString(R.string.simkl_clientSecret)
	private val contentTypeHints = LinkedHashMap<Long, SimklEndpoint>()

	override val oauthUrl: String
		get() = "$BASE_WEB_URL/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=$REDIRECT_URI"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		require(!code.isNullOrBlank()) { "Simkl authorization code is missing" }
		check(clientSecret.isNotBlank()) { "Missing Simkl client secret" }
		val payload = JSONObject().apply {
			put("code", code)
			put("client_id", clientId)
			put("client_secret", clientSecret)
			put("redirect_uri", REDIRECT_URI)
			put("grant_type", "authorization_code")
		}
		val request = Request.Builder()
			.url("$BASE_API_URL/oauth/token")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.optString("refresh_token").ifBlank { null }
	}

	override suspend fun loadUser(): ScrobblerUser {
		return loadUserProfile().user
	}

	override suspend fun loadUserProfile(): ScrobblerUserProfile {
		val request = Request.Builder()
			.url("$BASE_API_URL/users/settings")
			.get()
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		val account = response.getJSONObject("account")
		val user = response.getJSONObject("user")
		val profile = ScrobblerUser(
			id = account.getLong("id"),
			nickname = user.optString("name").ifBlank { "Simkl" },
			avatar = user.optString("avatar").ifBlank { null },
			service = ScrobblerService.SIMKL,
		).also { storage.user = it }
		val statsRequest = Request.Builder()
			.url("$BASE_API_URL/users/${profile.id}/stats")
			.get()
			.build()
		val statsResponse = okHttp.newCall(statsRequest).await().parseJson()
		val animeStats = statsResponse.optJSONObject("anime")
		val tvStats = statsResponse.optJSONObject("tv")
		val moviesStats = statsResponse.optJSONObject("movies")
		return ScrobblerUserProfile(
			user = profile,
			stats = ScrobblerUserStats(
				animeCount = animeStats?.optJSONObject("completed").optIntOrNull("count"),
				tvCount = tvStats?.optJSONObject("completed").optIntOrNull("count"),
				movieCount = moviesStats?.optJSONObject("completed").optIntOrNull("count"),
				episodesWatched = animeStats?.optJSONObject("completed").optIntOrNull("watched_episodes_count"),
				tvEpisodesWatched = tvStats?.optJSONObject("completed").optIntOrNull("watched_episodes_count"),
			),
		)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		db.deleteScrobblingByWorkOrManga(ScrobblerService.SIMKL.id, mangaId, workResolver)
	}

	suspend fun getDiscoveryItems(
		categoryId: String,
		page: Int,
		calendarDateMillis: Long? = null,
	): List<SimklCatalogItem> {
		val category = SimklDiscoveryCategory.fromId(categoryId) ?: return emptyList()
		if (page > 0 && !category.supportsPaging) {
			return emptyList()
		}
		val selectedDate = calendarDateMillis?.let {
			Instant.ofEpochMilli(it)
				.atZone(ZoneId.systemDefault())
				.toLocalDate()
				.toString()
		}
		val request = Request.Builder()
			.url(category.buildUrl(clientId, page, selectedDate))
			.get()
			.build()
		return okHttp.newCall(request).await().parseJsonArray().mapJSONNotNull { json ->
			json.toCatalogItem(category)
		}
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty()) {
			return emptyList()
		}
		val page = (offset / SEARCH_PAGE_SIZE) + 1
		val pageOffset = offset % SEARCH_PAGE_SIZE
		val merged = SimklEndpoint.entries.flatMap { endpoint ->
			val request = Request.Builder()
				.url(endpoint.buildSearchUrl(clientId, normalizedQuery, page, SEARCH_PAGE_SIZE))
				.get()
				.build()
			runCatching {
				okHttp.newCall(request).await().parseJsonArray().mapJSONNotNull { json ->
					json.toScrobblerContent(endpoint)
				}
			}.getOrElse { emptyList() }
		}.distinctBy { it.id }
		return if (pageOffset == 0) merged else merged.drop(pageOffset)
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val endpoint = contentTypeHints[id] ?: resolveEndpoint(id)
		return requestContentInfo(id, endpoint)
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val endpoint = resolveEndpoint(content)
		contentTypeHints[content.id] = endpoint
		postWatchlistUpdate(
			endpoint = endpoint,
			targetId = content.id,
			status = DEFAULT_WATCHLIST_STATUS,
			watchedAt = null,
		)
		saveRate(
			mangaId = mangaId,
			targetId = content.id,
			endpoint = endpoint,
			status = DEFAULT_WATCHLIST_STATUS,
			chapter = 0,
			comment = null,
			rating = 0f,
		)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.SIMKL.id, mangaId, workResolver)
		requireNotNull(entity) { "Scrobbling info for manga $mangaId not found" }
		val endpoint = contentTypeHints[entity.targetId] ?: resolveEndpoint(entity.targetId)
		if (endpoint == SimklEndpoint.MOVIES) {
			postWatchlistUpdate(
				endpoint = endpoint,
				targetId = entity.targetId,
				status = "completed",
				watchedAt = nowUtc(),
			)
			saveRate(
				mangaId = mangaId,
				targetId = entity.targetId,
				endpoint = endpoint,
				status = "completed",
				chapter = WATCHED_MOVIE_PROGRESS,
				comment = entity.comment,
				rating = entity.rating,
			)
			return
		}
		postHistoryUpdate(
			targetId = entity.targetId,
			episode = chapter,
			status = null,
			comment = entity.comment,
		)
		saveRate(
			mangaId = mangaId,
			targetId = entity.targetId,
			endpoint = endpoint,
			status = entity.status,
			chapter = chapter,
			comment = entity.comment,
			rating = entity.rating,
		)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.SIMKL.id, mangaId, workResolver)
		requireNotNull(entity) { "Scrobbling info for manga $mangaId not found" }
		val endpoint = contentTypeHints[entity.targetId] ?: resolveEndpoint(entity.targetId)
		val resolvedStatus = status ?: entity.status
		val resolvedComment = comment ?: entity.comment
		if (resolvedStatus != entity.status) {
			postWatchlistUpdate(
				endpoint = endpoint,
				targetId = entity.targetId,
				status = resolvedStatus,
				watchedAt = resolvedStatus.takeIf { it == "completed" }?.let { nowUtc() },
			)
		}
		if (resolvedComment != null && resolvedComment != entity.comment) {
			postHistoryUpdate(
				targetId = entity.targetId,
				episode = null,
				status = null,
				comment = resolvedComment,
			)
		}
		when {
			rating > 0f -> postRatingUpdate(endpoint, entity.targetId, rating)
			entity.rating > 0f -> removeRating(endpoint, entity.targetId)
		}
		saveRate(
			mangaId = mangaId,
			targetId = entity.targetId,
			endpoint = endpoint,
			status = resolvedStatus,
			chapter = entity.chapter,
			comment = resolvedComment,
			rating = rating.coerceIn(0f, 1f),
		)
	}

	suspend fun syncLibraryFromRemote(): Int {
		val latestActivities = fetchActivities()
		val savedActivities = loadSavedActivities()
		if (savedActivities?.all.isNullOrBlank()) {
			val synced = performInitialLibrarySync()
			saveActivities(latestActivities)
			return synced
		}
		if (savedActivities?.all == latestActivities.all) {
			return 0
		}

		val dao = db.getScrobblingDao()
		val existingByTargetId = dao.findAllByScrobbler(ScrobblerService.SIMKL.id)
			.preferredScrobblingByTargetId()
			.toMutableMap()
		val delta = fetchAllItemsResponse(
			type = null,
			dateFrom = savedActivities.all,
			idsOnly = false,
		)
		val changedCount = delta?.let {
			applyAllItemsDelta(it, existingByTargetId)
		} ?: 0
		saveActivities(latestActivities)
		return changedCount
	}

	private fun JSONObject.toCatalogItem(category: SimklDiscoveryCategory): SimklCatalogItem? {
		val nested = optJSONObject("show") ?: optJSONObject("movie")
		val ids = nested?.optJSONObject("ids") ?: optJSONObject("ids")
		val remoteId = ids.optLongOrNull("simkl_id")
			?: ids.optLongOrNull("simkl")
			?: return null
		contentTypeHints[remoteId] = category.endpointType()
		val title = optString("title").ifBlank {
			nested?.optString("title").orEmpty()
		}.ifBlank {
			return null
		}
		val altTitle = optString("en_title").ifBlank {
			nested?.optString("en_title").orEmpty()
		}.ifBlank {
			null
		}
		val poster = optString("poster").ifBlank {
			nested?.optString("poster").orEmpty()
		}.ifBlank {
			null
		}
		val year = optIntOrNull("year") ?: nested?.optIntOrNull("year")
		val status = optString("release_status").ifBlank {
			optJSONObject("status")?.optString("name").orEmpty()
		}.ifBlank {
			null
		}
		val airs = optJSONObject("airs")
		val airTime = listOfNotNull(
			airs?.optString("day").normalizeBlank(),
			airs?.optString("time").normalizeBlank(),
		).joinToString(" ").ifBlank { null }
		val subtitle = listOfNotNull(
			status?.formatLabel(),
			year?.toString(),
			airTime,
		).joinToString(" · ").ifBlank { null }
		val score = optFloatOrNull("rating")
			?: optJSONObject("ratings")
				?.optJSONObject("simkl")
				?.optFloatOrNull("rating")
		val slug = ids.optString("slug").normalizeBlank()
		return SimklCatalogItem(
			remoteId = remoteId,
			title = title,
			altTitle = altTitle,
			coverUrl = poster?.let(::buildPosterUrl),
			subtitle = subtitle,
			score = score,
			url = category.buildWebUrl(remoteId, slug),
		)
	}

	private fun JSONObject.toScrobblerContent(defaultEndpoint: SimklEndpoint): ScrobblerContent? {
		val ids = optJSONObject("ids")
		val remoteId = ids.optLongOrNull("simkl_id") ?: ids.optLongOrNull("simkl") ?: return null
		val endpoint = SimklEndpoint.fromApiValue(optString("endpoint_type")) ?: defaultEndpoint
		contentTypeHints[remoteId] = endpoint
		val title = optString("title").normalizeBlank() ?: return null
		val altName = optString("title_en").normalizeBlank()
			?: optString("en_title").normalizeBlank()
			?: optJSONArray("all_titles")?.firstNonBlankTitle(exclude = title)
		val url = optString("url").normalizeBlank()?.toAbsoluteSimklUrl()
			?: endpoint.buildWebUrl(remoteId, ids?.optString("slug").normalizeBlank())
		return ScrobblerContent(
			id = remoteId,
			name = title,
			altName = altName,
			cover = optString("poster").normalizeBlank()?.let(::buildPosterUrl),
			url = url,
			mediaType = endpoint.detailPath,
			primaryTitle = title,
			secondaryTitle = altName,
			score = optFloatOrNull("rating")
				?: optJSONObject("ratings")
					?.optJSONObject("simkl")
					?.optFloatOrNull("rating"),
			scoreMax = 10f,
				totalEpisodes = optIntOrNull("total_episodes"),
		)
	}

	private suspend fun postWatchlistUpdate(
		endpoint: SimklEndpoint,
		targetId: Long,
		status: String?,
		watchedAt: String?,
	) {
		val payload = JSONObject().apply {
			put(endpoint.listKey(), JSONArray().apply {
				put(JSONObject().apply {
					put("to", status ?: DEFAULT_WATCHLIST_STATUS)
					watchedAt?.let { put("watched_at", it) }
					put("ids", JSONObject().apply {
						put("simkl", targetId)
					})
				})
			})
		}
		val request = Request.Builder()
			.url("$BASE_API_URL/sync/add-to-list")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		okHttp.newCall(request).await().parseRaw()
	}

	private suspend fun postHistoryUpdate(
		targetId: Long,
		episode: Int?,
		status: String?,
		comment: String?,
	) {
		val payload = JSONObject().apply {
			put("shows", JSONArray().apply {
				put(JSONObject().apply {
					status?.let { put("status", it) }
					comment?.takeIf { it.isNotBlank() }?.let { memo ->
						put("memo", JSONObject().apply {
							put("text", memo.take(140))
							put("is_private", false)
						})
					}
					put("ids", JSONObject().apply {
						put("simkl", targetId)
					})
					if (episode != null) {
						put("episodes", JSONArray().apply {
							put(JSONObject().apply {
								put("number", episode)
								put("watched_at", nowUtc())
							})
						})
					}
				})
			})
		}
		val request = Request.Builder()
			.url("$BASE_API_URL/sync/history")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		okHttp.newCall(request).await().parseRaw()
	}

	private suspend fun postRatingUpdate(endpoint: SimklEndpoint, targetId: Long, rating: Float) {
		val payload = JSONObject().apply {
			put(endpoint.listKey(), JSONArray().apply {
				put(JSONObject().apply {
					put("rating", rating.toSimklRating())
					put("rated_at", nowUtc())
					put("ids", JSONObject().apply {
						put("simkl", targetId)
					})
				})
			})
		}
		val request = Request.Builder()
			.url("$BASE_API_URL/sync/ratings")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		okHttp.newCall(request).await().parseRaw()
	}

	private suspend fun removeRating(endpoint: SimklEndpoint, targetId: Long) {
		val payload = JSONObject().apply {
			put(endpoint.listKey(), JSONArray().apply {
				put(JSONObject().apply {
					put("ids", JSONObject().apply {
						put("simkl", targetId)
					})
				})
			})
		}
		val request = Request.Builder()
			.url("$BASE_API_URL/sync/ratings/remove")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		okHttp.newCall(request).await().parseRaw()
	}

	private suspend fun resolveEndpoint(id: Long): SimklEndpoint {
		return contentTypeHints[id]
			?: tryResolveEndpoint(id)
			?: SimklEndpoint.ANIME
	}

	private fun resolveEndpoint(content: ScrobblerContent): SimklEndpoint {
		return SimklEndpoint.fromApiValue(content.mediaType)
			?: SimklEndpoint.fromUrl(content.url)
			?: contentTypeHints[content.id]
			?: SimklEndpoint.ANIME
	}

	private suspend fun tryResolveEndpoint(id: Long): SimklEndpoint? {
		for (endpoint in SimklEndpoint.entries) {
			val request = Request.Builder()
				.url(endpoint.buildDetailsUrl(clientId, id))
				.get()
				.build()
			val response = runCatching {
				okHttp.newCall(request).await().parseJson()
			}.getOrNull() ?: continue
			val resolvedId = response.optJSONObject("ids").optLongOrNull("simkl") ?: continue
			if (resolvedId == id) {
				contentTypeHints[id] = endpoint
				return endpoint
			}
		}
		return null
	}

	private suspend fun requestContentInfo(id: Long, endpoint: SimklEndpoint): ScrobblerContentInfo {
		val request = Request.Builder()
			.url(endpoint.buildDetailsUrl(clientId, id))
			.get()
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		contentTypeHints[id] = endpoint
		val remoteId = response.optJSONObject("ids").optLongOrNull("simkl") ?: id
		val episodes = if (endpoint == SimklEndpoint.MOVIES) {
			emptyList()
		} else {
			fetchEpisodes(remoteId, endpoint)
		}
		return response.toContentInfo(endpoint, remoteId, episodes)
	}

	private suspend fun fetchEpisodes(id: Long, endpoint: SimklEndpoint): List<ScrobblerContentInfo.EpisodeInfo> {
		val request = Request.Builder()
			.url(endpoint.buildEpisodesUrl(clientId, id))
			.get()
			.build()
		return runCatching {
			okHttp.newCall(request).await().parseJsonArray().mapJSONNotNull { json ->
				json.toEpisodeInfo(endpoint)
			}
		}.getOrElse { emptyList() }
	}

	private fun JSONObject.toContentInfo(
		endpoint: SimklEndpoint,
		remoteId: Long,
		episodes: List<ScrobblerContentInfo.EpisodeInfo>,
	): ScrobblerContentInfo {
		val ids = optJSONObject("ids")
		val title = optString("title").normalizeBlank() ?: remoteId.toString()
		val altTitle = optString("en_title").normalizeBlank()
		val genres = optJSONArray("genres")?.toStringList().orEmpty()
		val score = optJSONObject("ratings")
			?.optJSONObject("simkl")
			?.optFloatOrNull("rating")
			?: optFloatOrNull("rating")
		val rank = optIntOrNull("rank")
		val firstAired = optString("first_aired").normalizeBlank()
		val airs = optJSONObject("airs")
		val infobox = buildList {
			optString("status").normalizeBlank()?.let {
				add("Status" to it.formatLabel())
			}
			optString("anime_type").normalizeBlank()?.let {
				add("Type" to it.formatLabel())
			}
			optIntOrNull("year")?.let {
				add("Year" to it.toString())
			}
			firstAired?.let {
				add("First aired" to it)
			}
			listOfNotNull(
				airs?.optString("day").normalizeBlank(),
				airs?.optString("time").normalizeBlank(),
				airs?.optString("timezone").normalizeBlank(),
			).joinToString(" · ").ifBlank { null }?.let {
				add("Airs" to it)
			}
			optIntOrNull("runtime")?.let {
				add("Runtime" to "${it} min")
			}
			optString("network").normalizeBlank()?.let {
				add("Network" to it)
			}
			optString("country").normalizeBlank()?.let {
				add("Country" to it)
			}
			optString("certification").normalizeBlank()?.let {
				add("Certification" to it)
			}
			optIntOrNull("total_episodes")?.let {
				add("Episodes" to it.toString())
			}
		}
		return ScrobblerContentInfo(
			id = remoteId,
			name = title,
			cover = optString("poster").normalizeBlank()?.let(::buildPosterUrl).orEmpty(),
			url = optString("url").normalizeBlank()?.toAbsoluteSimklUrl()
				?: endpoint.buildWebUrl(remoteId, ids?.optString("slug").normalizeBlank()),
			descriptionHtml = optString("overview").normalizeBlank()?.replace("\n", "<br>") ?: "",
			contentType = endpoint.contentType,
			score = score,
			rank = rank,
			tags = genres,
			infoboxProperties = infobox,
			episodes = episodes,
			recommendations = optJSONArray("users_recommendations")?.toRecommendations(endpoint).orEmpty(),
			actions = optJSONArray("trailers")?.toTrailerActions().orEmpty(),
		)
	}

	private fun JSONObject.toEpisodeInfo(endpoint: SimklEndpoint): ScrobblerContentInfo.EpisodeInfo? {
		val ids = optJSONObject("ids")
		val remoteId = ids.optLongOrNull("simkl_id") ?: return null
		val season = optIntOrNull("season")
		val episodeNumber = optIntOrNull("episode")
		val number = buildString {
			if (season != null) {
				append("S")
				append(season.toString().padStart(2, '0'))
			}
			if (episodeNumber != null) {
				if (isNotEmpty()) {
					append("E")
				}
				append(episodeNumber.toString().padStart(2, '0'))
			}
			if (isEmpty()) {
				append(remoteId)
			}
		}
		val title = optString("title").normalizeBlank() ?: number
		val url = optString("url").normalizeBlank()?.toAbsoluteSimklUrl()
			?: buildEpisodeWebUrl(endpoint, remoteId, season, episodeNumber)
		return ScrobblerContentInfo.EpisodeInfo(
			number = number,
			title = title,
			url = url,
			thumbnailUrl = optString("img").normalizeBlank()?.let(::buildEpisodeImageUrl),
		)
	}

	private fun JSONArray.toRecommendations(endpoint: SimklEndpoint): List<ScrobblerContentInfo.RelatedWork> {
		return mapJSONNotNull { item ->
			val ids = item.optJSONObject("ids")
			val remoteId = ids.optLongOrNull("simkl") ?: return@mapJSONNotNull null
			val title = item.optString("title").normalizeBlank() ?: return@mapJSONNotNull null
			contentTypeHints[remoteId] = endpoint
			ScrobblerContentInfo.RelatedWork(
				id = remoteId,
				title = title,
				coverUrl = item.optString("poster").normalizeBlank()?.let(::buildPosterUrl).orEmpty(),
				relationship = item.optString("users_percent").normalizeBlank(),
				url = endpoint.buildWebUrl(remoteId, ids?.optString("slug").normalizeBlank()),
			)
		}
	}

	private fun JSONArray.toTrailerActions(): List<ScrobblerContentInfo.ExternalAction> {
		return mapJSONNotNull { item ->
			val youtube = item.optString("youtube").normalizeBlank() ?: return@mapJSONNotNull null
			val title = item.optString("name").normalizeBlank() ?: "Trailer"
			ScrobblerContentInfo.ExternalAction(
				title = title,
				url = "https://www.youtube.com/watch?v=$youtube",
			)
		}
	}

	private fun buildPosterUrl(poster: String): String {
		return "$IMAGE_PROXY_URL/posters/${poster}_m.webp"
	}

	private suspend fun fetchActivities(): SimklActivitySnapshot {
		val request = Request.Builder()
			.url("$BASE_API_URL/sync/activities")
			.get()
			.build()
		return okHttp.newCall(request).await().parseJson().toActivitySnapshot()
	}

	private suspend fun performInitialLibrarySync(): Int {
		val dao = db.getScrobblingDao()
		val existingByTargetId = dao.findAllByScrobbler(ScrobblerService.SIMKL.id)
			.preferredScrobblingByTargetId()
		val synced = buildList {
			for (type in SimklSyncType.entries) {
				addAll(
					fetchLibraryResponse(type = type, dateFrom = null)?.toScrobblerEntities(existingByTargetId).orEmpty(),
				)
			}
		}.distinctBy { it.targetId }
		db.withTransaction {
			dao.deleteByScrobbler(ScrobblerService.SIMKL.id)
			for (entity in synced) {
				db.upsertScrobbling(entity, workResolver)
			}
		}
		return synced.size
	}

	private suspend fun applyAllItemsDelta(
		response: JSONObject,
		existingByTargetId: MutableMap<Long, ScrobblingEntity>,
	): Int {
		val updates = response.toScrobblerEntities(existingByTargetId)
		if (updates.isEmpty()) {
			return 0
		}
		db.withTransaction {
			val dao = db.getScrobblingDao()
			for (entity in updates) {
				val normalized = db.upsertScrobbling(entity, workResolver)
				existingByTargetId[entity.targetId] = normalized
			}
		}
		return updates.size
	}

	private suspend fun applyListSyncDelta(
		type: SimklSyncType,
		dateFrom: String?,
		existingByTargetId: MutableMap<Long, ScrobblingEntity>,
	): Int {
		val response = fetchAllItemsResponse(type = type, dateFrom = dateFrom, idsOnly = false) ?: return 0
		val updates = response.toScrobblerEntities(existingByTargetId)
		if (updates.isEmpty()) {
			return 0
		}
		db.withTransaction {
			val dao = db.getScrobblingDao()
			for (entity in updates) {
				val normalized = db.upsertScrobbling(entity, workResolver)
				existingByTargetId[entity.targetId] = normalized
			}
		}
		return updates.size
	}

	private suspend fun applyRatingSyncDelta(
		type: SimklSyncType,
		dateFrom: String?,
		existingByTargetId: MutableMap<Long, ScrobblingEntity>,
	): Int {
		val response = fetchRatingsResponse(type, dateFrom) ?: return 0
		val updates = response.toRatingEntities(type.endpoint, existingByTargetId)
		if (updates.isEmpty()) {
			return 0
		}
		db.withTransaction {
			val dao = db.getScrobblingDao()
			for (entity in updates) {
				dao.upsert(entity)
				existingByTargetId[entity.targetId] = entity
			}
		}
		return updates.size
	}

	private suspend fun removeMissingEntries(
		type: SimklSyncType,
		existingByTargetId: MutableMap<Long, ScrobblingEntity>,
	): Int {
		val response = fetchAllItemsResponse(type = type, dateFrom = null, idsOnly = true)
		val remoteIds = response?.collectRemoteIds(type.endpoint).orEmpty()
		val targetIds = existingByTargetId.keys.filter { targetId ->
			resolveEndpoint(targetId) == type.endpoint
		}
		var removed = 0
		db.withTransaction {
			val dao = db.getScrobblingDao()
			for (targetId in targetIds) {
				if (targetId in remoteIds) {
					continue
				}
				for (entity in dao.findAllByTargetId(ScrobblerService.SIMKL.id, targetId)) {
					dao.delete(entity)
					removed++
				}
				existingByTargetId.remove(targetId)
			}
		}
		return removed
	}

	private suspend fun fetchAllItemsResponse(
		type: SimklSyncType?,
		dateFrom: String?,
		idsOnly: Boolean,
	): JSONObject? {
		val urlBuilder = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("sync")
			.addPathSegment("all-items")
		type?.let {
			urlBuilder.addPathSegment(it.apiType)
		}
		dateFrom?.let {
			urlBuilder.addQueryParameter("date_from", it)
		}
		if (idsOnly) {
			urlBuilder.addQueryParameter("extended", "simkl_ids_only")
		} else {
			urlBuilder.addQueryParameter("memos", "yes")
		}
		val request = Request.Builder()
			.url(urlBuilder.build())
			.get()
			.build()
		val raw = okHttp.newCall(request).await().parseRaw().trim()
		return raw.takeUnless { it.isEmpty() || it == "null" }?.let(::JSONObject)
	}

	private suspend fun fetchLibraryResponse(
		type: SimklSyncType,
		dateFrom: String?,
	): JSONObject? {
		val urlBuilder = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("sync")
			.addPathSegment(type.apiType)
		dateFrom?.let {
			urlBuilder.addQueryParameter("date_from", it)
		}
		val request = Request.Builder()
			.url(urlBuilder.build())
			.get()
			.build()
		val raw = okHttp.newCall(request).await().parseRaw().trim()
		return raw.takeUnless { it.isEmpty() || it == "null" }?.let(::JSONObject)
	}

	private suspend fun fetchRatingsResponse(type: SimklSyncType, dateFrom: String?): JSONObject? {
		val urlBuilder = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("sync")
			.addPathSegment("ratings")
			.addPathSegment(type.apiType)
		dateFrom?.let {
			urlBuilder.addQueryParameter("date_from", it)
		}
		val request = Request.Builder()
			.url(urlBuilder.build())
			.get()
			.build()
		val raw = okHttp.newCall(request).await().parseRaw().trim()
		return raw.takeUnless { it.isEmpty() || it == "null" }?.let(::JSONObject)
	}

	private fun loadSavedActivities(): SimklActivitySnapshot? {
		val groups = SimklSyncType.entries.associateWith { type ->
			SimklActivityGroup(
				all = storage[activityStorageKey(type, "all")],
				ratedAt = storage[activityStorageKey(type, "rated_at")],
				removedFromList = storage[activityStorageKey(type, "removed_from_list")],
			)
		}
		val hasAnyValue = groups.values.any { group ->
			group.all != null || group.ratedAt != null || group.removedFromList != null
		}
		val all = storage[KEY_ACTIVITY_ALL]
		return if (all == null && !hasAnyValue) {
			null
		} else {
			SimklActivitySnapshot(
				all = all,
				groups = groups,
			)
		}
	}

	private fun saveActivities(snapshot: SimklActivitySnapshot) {
		storage[KEY_ACTIVITY_ALL] = snapshot.all
		for ((type, group) in snapshot.groups) {
			storage[activityStorageKey(type, "all")] = group.all
			storage[activityStorageKey(type, "rated_at")] = group.ratedAt
			storage[activityStorageKey(type, "removed_from_list")] = group.removedFromList
		}
	}

	private fun JSONArray?.toScrobblingEntities(
		endpoint: SimklEndpoint,
		existingByTargetId: Map<Long, ScrobblingEntity>,
	): List<ScrobblingEntity> {
		if (this == null) {
			return emptyList()
		}
		return mapJSONNotNull { item ->
			val media = when (endpoint) {
				SimklEndpoint.MOVIES -> item.optJSONObject("movie")
				else -> item.optJSONObject("show")
			} ?: return@mapJSONNotNull null
			val targetId = media.optJSONObject("ids").optLongOrNull("simkl_id")
				?: media.optJSONObject("ids").optLongOrNull("simkl")
				?: return@mapJSONNotNull null
			contentTypeHints[targetId] = endpoint
			val status = item.optString("status").normalizeBlank()
			val chapter = when (endpoint) {
				SimklEndpoint.MOVIES -> item.optString("status").takeIf { it == "completed" }?.let { WATCHED_MOVIE_PROGRESS } ?: 0
				else -> item.optIntOrNull("watched_episodes_count")
					?: item.optString("last_watched").parseLastWatchedEpisode()
					?: 0
			}
			ScrobblingEntity(
				scrobbler = ScrobblerService.SIMKL.id,
				id = targetId.toInt(),
				mangaId = existingByTargetId[targetId]?.mangaId ?: 0L,
				targetId = targetId,
				status = status,
				chapter = chapter,
				comment = item.optJSONObject("memo")?.optString("text").normalizeBlank(),
				rating = ((item.optIntOrNull("user_rating") ?: 0) / 10f).coerceIn(0f, 1f),
				mediaType = endpoint.detailPath,
				remoteTitle = media.optString("title").normalizeBlank(),
				remoteCoverUrl = media.optString("poster").normalizeBlank()?.let(::buildPosterUrl),
				remoteUrl = endpoint.buildWebUrl(targetId, media.optJSONObject("ids")?.optString("slug").normalizeBlank()),
			)
		}
	}

	private fun JSONObject.toScrobblerEntities(existingByTargetId: Map<Long, ScrobblingEntity>): List<ScrobblingEntity> {
		return buildList {
			addAll(optJSONArray(SimklSyncType.ANIME.responseKey).toScrobblingEntities(SimklEndpoint.ANIME, existingByTargetId))
			addAll(optJSONArray(SimklSyncType.SHOWS.responseKey).toScrobblingEntities(SimklEndpoint.TV, existingByTargetId))
			addAll(optJSONArray(SimklSyncType.MOVIES.responseKey).toScrobblingEntities(SimklEndpoint.MOVIES, existingByTargetId))
		}
	}

	private fun JSONObject.toRatingEntities(
		endpoint: SimklEndpoint,
		existingByTargetId: Map<Long, ScrobblingEntity>,
	): List<ScrobblingEntity> {
		val items = optJSONArray(
			SimklSyncType.entries.first { it.endpoint == endpoint }.responseKey,
		) ?: return emptyList()
		return items.mapJSONNotNull { item ->
			val media = when (endpoint) {
				SimklEndpoint.MOVIES -> item.optJSONObject("movie")
				else -> item.optJSONObject("show")
			} ?: return@mapJSONNotNull null
			val targetId = media.optJSONObject("ids").optLongOrNull("simkl_id")
				?: media.optJSONObject("ids").optLongOrNull("simkl")
				?: return@mapJSONNotNull null
			contentTypeHints[targetId] = endpoint
			val existing = existingByTargetId[targetId]
			ScrobblingEntity(
				scrobbler = ScrobblerService.SIMKL.id,
				id = targetId.toInt(),
				mangaId = existing?.mangaId ?: 0L,
				targetId = targetId,
				status = item.optString("status").normalizeBlank() ?: existing?.status,
				chapter = when (endpoint) {
					SimklEndpoint.MOVIES -> item.optString("status")
						.takeIf { it == "completed" }
						?.let { WATCHED_MOVIE_PROGRESS }
						?: existing?.chapter
						?: 0
					else -> item.optString("last_watched").parseLastWatchedEpisode()
						?: existing?.chapter
						?: 0
				},
				comment = existing?.comment,
				rating = ((item.optIntOrNull("user_rating") ?: 0) / 10f).coerceIn(0f, 1f),
				mediaType = endpoint.detailPath,
				remoteTitle = media.optString("title").normalizeBlank() ?: existing?.remoteTitle,
				remoteCoverUrl = media.optString("poster").normalizeBlank()?.let(::buildPosterUrl)
					?: existing?.remoteCoverUrl,
				remoteUrl = endpoint.buildWebUrl(targetId, media.optJSONObject("ids")?.optString("slug").normalizeBlank()),
			)
		}
	}

	private fun JSONObject.collectRemoteIds(endpoint: SimklEndpoint): Set<Long> {
		val type = SimklSyncType.entries.first { it.endpoint == endpoint }
		val items = optJSONArray(type.responseKey) ?: return emptySet()
		return items.mapJSONNotNull { item ->
			val media = when (endpoint) {
				SimklEndpoint.MOVIES -> item.optJSONObject("movie")
				else -> item.optJSONObject("show")
			}
			val targetId = media?.optJSONObject("ids").optLongOrNull("simkl_id")
				?: media?.optJSONObject("ids").optLongOrNull("simkl")
			targetId?.also { contentTypeHints[it] = endpoint }
		}.toSet()
	}

	private fun JSONObject.toActivitySnapshot(): SimklActivitySnapshot {
		return SimklActivitySnapshot(
			all = optString("all").normalizeBlank(),
			groups = SimklSyncType.entries.associateWith { type ->
				val group = optJSONObject(type.activityKey)
				SimklActivityGroup(
					all = group?.optString("all").normalizeBlank(),
					ratedAt = group?.optString("rated_at").normalizeBlank(),
					removedFromList = group?.optString("removed_from_list").normalizeBlank(),
				)
			},
		)
	}

	private suspend fun saveRate(
		mangaId: Long,
		targetId: Long,
		endpoint: SimklEndpoint,
		status: String?,
		chapter: Int,
		comment: String?,
		rating: Float,
	) {
		contentTypeHints[targetId] = endpoint
		db.upsertScrobblingForManga(
			ScrobblingEntity(
				scrobbler = ScrobblerService.SIMKL.id,
				id = targetId.toInt(),
				mangaId = mangaId,
				targetId = targetId,
				status = status,
				chapter = chapter,
				comment = comment,
				rating = rating.coerceIn(0f, 1f),
			),
			workResolver,
			mangaId = mangaId,
		)
	}

	private fun buildEpisodeImageUrl(image: String): String {
		return "$IMAGE_PROXY_URL/episodes/${image}_w.jpg"
	}

	private fun buildEpisodeWebUrl(
		endpoint: SimklEndpoint,
		remoteId: Long,
		season: Int?,
		episode: Int?,
	): String {
		val seasonSegment = season?.let { "/season-$it" }.orEmpty()
		val episodeSegment = episode?.let { "/episode-$it" }.orEmpty()
		return "$BASE_WEB_URL/${endpoint.webPath}/$remoteId$seasonSegment$episodeSegment"
	}

	private fun JSONObject?.optLongOrNull(name: String): Long? {
		if (this == null || !has(name) || isNull(name)) {
			return null
		}
		return optLong(name).takeIf { it > 0L }
	}

	private fun JSONObject?.optIntOrNull(name: String): Int? {
		if (this == null || !has(name) || isNull(name)) {
			return null
		}
		return optInt(name).takeIf { it > 0 }
	}

	private fun JSONObject?.optFloatOrNull(name: String): Float? {
		if (this == null || !has(name) || isNull(name)) {
			return null
		}
		return optDouble(name).takeIf { !it.isNaN() }?.toFloat()
	}

	private fun JSONArray.firstNonBlankTitle(exclude: String): String? {
		for (index in 0 until length()) {
			val value = optString(index).normalizeBlank() ?: continue
			if (!value.equals(exclude, ignoreCase = true)) {
				return value
			}
		}
		return null
	}

	private fun JSONArray.toStringList(): List<String> = buildList(length()) {
		for (index in 0 until length()) {
			optString(index).normalizeBlank()?.let(::add)
		}
	}

	private fun SimklEndpoint.listKey(): String = if (this == SimklEndpoint.MOVIES) "movies" else "shows"

	private fun String?.normalizeBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

	private fun activityStorageKey(type: SimklSyncType, field: String): String {
		return "sync_activity_${type.apiType}_$field"
	}

	private fun String?.parseLastWatchedEpisode(): Int? {
		val value = this.normalizeBlank() ?: return null
		return Regex("""E(\d+)$""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
	}

	private fun Float.toSimklRating(): Int {
		return (coerceIn(0f, 1f) * 10f).roundToInt().coerceIn(1, 10)
	}

	private fun nowUtc(): String = java.time.Instant.now().toString()

	private fun String.toAbsoluteSimklUrl(): String {
		return if (startsWith("http://") || startsWith("https://")) {
			this
		} else {
			"$BASE_WEB_URL$this"
		}
	}

	private fun String.formatLabel(): String {
		return split('_', '-', ' ')
			.filter { it.isNotBlank() }
			.joinToString(" ") { part ->
				part.lowercase().replaceFirstChar { it.titlecase() }
			}
	}
}
