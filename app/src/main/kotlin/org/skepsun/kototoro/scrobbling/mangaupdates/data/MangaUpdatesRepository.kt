package org.skepsun.kototoro.scrobbling.mangaupdates.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingPreview
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingForManga
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserStats
import org.skepsun.kototoro.work.domain.WorkResolver
import java.io.IOException
import java.time.LocalDate
import kotlin.math.roundToInt

private const val TAG = "MURepo"
private const val BASE_API_URL = "https://api.mangaupdates.com/v1"
private const val BASE_WEB_URL = "https://www.mangaupdates.com"
private const val COMMENTS_PAGE_SIZE = 10
private const val REVIEW_PAGE_SIZE = 3
private const val DISCOVERY_PAGE_SIZE = 25
private const val USER_RATING_SYNC_CONCURRENCY = 4
private val CONTENT_TYPE = "application/json".toMediaType()

internal fun toMangaUpdatesRating(rating: Float): Int {
	return (rating.coerceIn(0f, 1f) * 10f).roundToInt().coerceIn(0, 10)
}

internal fun normalizeMangaUpdatesRating(rawValue: Double): Float {
	if (!rawValue.isFinite()) return 0f
	return (rawValue / 10.0).coerceIn(0.0, 1.0).toFloat()
}

internal fun parseMangaUpdatesUserRating(response: JSONObject): Float? {
	return response.optNormalizedMangaUpdatesRating("rating")
		?: response.optNormalizedMangaUpdatesRating("user_rating")
		?: response.optJSONObject("record")?.optNormalizedMangaUpdatesRating("rating")
		?: response.optJSONObject("record")?.optNormalizedMangaUpdatesRating("user_rating")
		?: response.optJSONObject("status")?.optNormalizedMangaUpdatesRating("rating")
}

private fun JSONObject.optNormalizedMangaUpdatesRating(key: String): Float? {
	if (!has(key) || isNull(key)) return null
	val raw = opt(key) ?: return null
	return when (raw) {
		is Number -> normalizeMangaUpdatesRating(raw.toDouble())
		is String -> raw.toDoubleOrNull()?.let(::normalizeMangaUpdatesRating)
		is JSONObject -> raw.optNumericMangaUpdatesRating("rating")
			?: raw.optNumericMangaUpdatesRating("value")
			?: raw.optNumericMangaUpdatesRating("score")
		else -> null
	}
}

private fun JSONObject.optNumericMangaUpdatesRating(key: String): Float? {
	if (!has(key) || isNull(key)) return null
	val raw = opt(key) ?: return null
	return when (raw) {
		is Number -> normalizeMangaUpdatesRating(raw.toDouble())
		is String -> raw.toDoubleOrNull()?.let(::normalizeMangaUpdatesRating)
		else -> null
	}
}

class MangaUpdatesRepository(
	private val okHttp: OkHttpClient,
	private val cookieJar: MutableCookieJar,
	private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

	override val oauthUrl: String = "kototoro+mangaupdates://auth"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null || storage.user != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		if (code == null) return

		val username = code.substringBefore(';')
		Log.d(TAG, "authorize: attempting login for user=$username")
		val password = code.substringAfter(';')

		val payload = JSONObject().apply {
			put("username", username)
			put("password", password)
		}

		val request = Request.Builder()
			.put(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/account/login")
		
		val response = okHttp.newCall(request.build()).await().use { rawResponse ->
			val body = rawResponse.body.string()
			val json = body.toJsonObject("login")
			if (!rawResponse.isSuccessful || json.isExceptionResponse()) {
				Log.e(TAG, "authorize: login failed, code=${rawResponse.code}")
				throw IOException(
					json.extractFailureReason()
						?: "MangaUpdates login failed with HTTP ${rawResponse.code}",
				)
			}
			json
		}
		storage.accessToken = response.extractSessionToken()
		// If no token in body, try loginWithCookie for cookie-based session
		if (storage.accessToken == null) {
			Log.d(TAG, "authorize: no session_token in login response, trying loginWithCookie")
			try {
				val cookieRequest = Request.Builder()
					.put(payload.toString().toRequestBody(CONTENT_TYPE))
					.url("$BASE_API_URL/account/loginWithCookie")
				okHttp.newCall(cookieRequest.build()).await().use { cookieResponse ->
					if (cookieResponse.isSuccessful) {
						val cookieJson = cookieResponse.body.string().toJsonObject("loginWithCookie")
						storage.accessToken = cookieJson.extractSessionToken()
					}
				}
			} catch (e: Exception) {
				Log.w(TAG, "authorize: loginWithCookie also failed", e)
			}
		}
		if (storage.accessToken == null && !hasSessionCookies()) {
			Log.e(TAG, "authorize: login succeeded but no session token or cookies")
			throw IOException(
				response.extractFailureReason()
					?: "MangaUpdates login succeeded but no reusable session was returned",
			)
		}
		Log.d(TAG, "authorize: login success, hasToken=${storage.accessToken != null}, hasCookies=${hasSessionCookies()}")
	}

	override suspend fun loadUser(): ScrobblerUser {
		return loadUserProfile().user
	}

	override suspend fun loadUserProfile(): ScrobblerUserProfile {
		val request = Request.Builder()
			.get()
			.url("$BASE_API_URL/account/profile")
		val responseStr = okHttp.newCall(request.build()).await().parseRaw()
		val response = try {
			JSONObject(responseStr)
		} catch (e: JSONException) {
			throw IOException("Invalid profile response: ${responseStr.take(200)}", e)
		}

		val avatarUrl = response.optJSONObject("avatar")?.let { avatar ->
			avatar.optJSONObject("url")?.getStringOrNull("original")
				?: avatar.getStringOrNull("url")
		}
		val user = ScrobblerUser(
			id = response.optLong("user_id", 0L),
			nickname = response.optString("username", "User"),
			avatar = avatarUrl,
			service = ScrobblerService.MANGAUPDATES,
		).also { storage.user = it }

		val entities = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.MANGAUPDATES.id)
		val ratedEntries = entities.filter { it.rating > 0f }
		val stats = ScrobblerUserStats(
			mangaCount = entities.size.takeIf { it > 0 },
			chaptersRead = entities.sumOf { it.chapter }.takeIf { it > 0 },
			mangaMeanScore = ratedEntries
				.takeIf { it.isNotEmpty() }
				?.let { rated -> rated.sumOf { it.rating.toDouble() } / rated.size },
		)
		return ScrobblerUserProfile(
			user = user,
			stats = stats.takeIf { it.mangaCount != null || it.chaptersRead != null || it.mangaMeanScore != null },
		)
	}

	private fun JSONObject.extractSessionToken(): String? {
		// Try top-level session_token first
		getStringOrNull("session_token")?.takeIf { it.isNotBlank() }?.let { return it }
		// Try context.session_token (legacy path)
		optJSONObject("context")
			?.getStringOrNull("session_token")
			?.takeIf { it.isNotBlank() }
			?.let { return it }
		// Try context wrapped in "data"
		optJSONObject("data")
			?.getStringOrNull("session_token")
			?.takeIf { it.isNotBlank() }
			?.let { return it }
		return null
	}

	private fun JSONObject.extractFailureReason(): String? {
		return getStringOrNull("reason")?.takeIf { it.isNotBlank() }
	}

	private fun JSONObject.isExceptionResponse(): Boolean {
		return optString("status").equals("exception", ignoreCase = true)
	}

	private fun String.toJsonObject(responseName: String): JSONObject {
		return try {
			JSONObject(this)
		} catch (e: JSONException) {
			throw IOException("Invalid MangaUpdates $responseName response: ${take(200)}", e)
		}
	}

	private fun hasSessionCookies(): Boolean {
		return cookieJar.loadForRequest("$BASE_API_URL/".toHttpUrl()).isNotEmpty() ||
			cookieJar.loadForRequest("$BASE_WEB_URL/".toHttpUrl()).isNotEmpty()
	}

	override fun logout() {
		runCatching {
			val request = Request.Builder()
				.post(ByteArray(0).toRequestBody(null))
				.url("$BASE_API_URL/account/logout")
			okHttp.newCall(request.build()).execute()
		}
		cookieJar.removeCookies("$BASE_API_URL/".toHttpUrl(), null)
		cookieJar.removeCookies("$BASE_WEB_URL/".toHttpUrl(), null)
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		db.deleteScrobblingByWorkOrManga(ScrobblerService.MANGAUPDATES.id, mangaId, workResolver)
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val payload = JSONObject().apply {
			put("search", query)
			put("page", (offset / 100) + 1)
			put("perpage", 100)
		}
		
		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/series/search")

		val response = okHttp.newCall(request.build()).await().parseJson()
		val results = response.optJSONArray("results") ?: return emptyList()

		val mapped = mutableListOf<ScrobblerContent>()
		for (i in 0 until results.length()) {
			val result = results.getJSONObject(i)
			val record = result.getJSONObject("record")
			mapped.add(
				ScrobblerContent(
					id = record.getLong("series_id"),
					name = record.getString("title"),
					altName = null,
					cover = record.optJSONObject("image")?.optJSONObject("url")?.getStringOrNull("original"),
					url = record.getString("url"),
					mediaType = record.optString("type").takeIf { it.isNotBlank() },
					subtitle = record.optString("year").takeIf { it.isNotBlank() },
					isBestMatch = record.getString("title").equals(query, ignoreCase = true)
				)
			)
		}
		return mapped
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url("$BASE_API_URL/series/$id")
		
		val response = okHttp.newCall(request.build()).await().parseJson()
		return parseSeriesDetails(response)
	}

	suspend fun getEntityInfo(
		entityType: EntityType,
		id: Long,
		urlHint: String? = null,
	): ScrobblerContentInfo? {
		return when (entityType) {
			EntityType.PERSON -> getAuthorInfo(id = id, urlHint = urlHint)
			else -> null
		}
	}

	suspend fun searchEntities(
		entityType: EntityType,
		query: String,
		page: Int,
		limit: Int = 10,
	): List<ScrobblerContent> {
		if (entityType != EntityType.PERSON) {
			return emptyList()
		}
		val payload = JSONObject().apply {
			put("search", query)
			put("page", page.coerceAtLeast(0) + 1)
			put("perpage", limit)
		}
		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/authors/search")
		val response = okHttp.newCall(request.build()).await().parseJson()
		val results = response.optJSONArray("results") ?: return emptyList()
		val mapped = ArrayList<ScrobblerContent>(minOf(results.length(), limit))
		for (i in 0 until minOf(results.length(), limit)) {
			val record = results.optJSONObject(i)?.optJSONObject("record") ?: continue
			mapped += ScrobblerContent(
				id = record.getLong("id"),
				name = record.getString("name"),
				altName = null,
				cover = null,
				url = record.getStringOrNull("url") ?: "$BASE_WEB_URL/author/${record.getLong("id")}",
			)
		}
		return mapped
	}

	suspend fun getRankings(
		orderby: String,
		page: Int,
		type: String? = null,
		genre: String? = null,
	): List<ScrobblerContent> {
		val payload = JSONObject().apply {
			put("page", page)
			put("perpage", DISCOVERY_PAGE_SIZE)
			put("orderby", orderby)
			if (type != null) {
				put("type", JSONArray().apply { put(type) })
			}
			if (genre != null) {
				put("genre", JSONArray().apply { put(genre) })
			}
		}

		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/series/search")

		val response = okHttp.newCall(request.build()).await().parseJson()
		val results = response.optJSONArray("results") ?: return emptyList()

		val mapped = mutableListOf<ScrobblerContent>()
		for (i in 0 until results.length()) {
			val result = results.getJSONObject(i)
			val record = result.getJSONObject("record")
			val rating = record.optDouble("bayesian_rating", 0.0)
			val year = record.optString("year", "").ifBlank { null }
			mapped.add(
				ScrobblerContent(
					id = record.getLong("series_id"),
					name = record.getString("title"),
					altName = null,
					cover = record.optJSONObject("image")?.optJSONObject("url")?.getStringOrNull("original"),
					url = record.getString("url"),
					mediaType = record.optString("type").takeIf { it.isNotBlank() },
					subtitle = year,
					score = rating.takeIf { it > 0.0 }?.toFloat(),
					scoreMax = 10f,
					isBestMatch = false,
				)
			)
		}
		return mapped
	}

	suspend fun getDailyReleases(date: LocalDate, page: Int): List<ScrobblerContent> {
		val releaseDate = date.toString()
		val payload = JSONObject().apply {
			put("page", page)
			put("perpage", DISCOVERY_PAGE_SIZE)
			put("orderby", "date")
			put("start_date", releaseDate)
			put("end_date", releaseDate)
			put("include_metadata", true)
		}

		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/releases/search")

		val response = okHttp.newCall(request.build()).await().parseJson()
		val results = response.optJSONArray("results") ?: return emptyList()
		val mapped = ArrayList<ScrobblerContent>(results.length())
		for (i in 0 until results.length()) {
			val result = results.optJSONObject(i) ?: continue
			val record = result.optJSONObject("record") ?: continue
			val series = result.optJSONObject("metadata")?.optJSONObject("series") ?: continue
			val seriesId = series.optLong("series_id", 0L).takeIf { it > 0L } ?: continue
			val seriesTitle = series.getStringOrNull("title")
			val releaseTitle = record.optString("title").takeIf { it.isNotBlank() }
			val chapter = record.optString("chapter").takeIf { it.isNotBlank() }
			val volume = record.optString("volume").takeIf { it.isNotBlank() }
			val groupNames = record.optJSONArray("groups")?.let { groups ->
				buildList {
					for (groupIndex in 0 until minOf(groups.length(), 2)) {
						groups.optJSONObject(groupIndex)
							?.optString("name")
							?.takeIf { it.isNotBlank() }
							?.let(::add)
					}
				}
			}.orEmpty()
			val releaseLabel = listOfNotNull(
				volume?.let { "Vol $it" },
				chapter?.let { "Ch $it" },
			).joinToString(" ").takeIf { it.isNotBlank() }
			val releaseDateText = record.optString("release_date").takeIf { it.isNotBlank() }
			mapped += ScrobblerContent(
				id = seriesId,
				name = seriesTitle ?: releaseTitle.orEmpty(),
				altName = releaseTitle?.takeIf { it != seriesTitle },
				cover = null,
				url = series.getStringOrNull("url") ?: "$BASE_WEB_URL/series/$seriesId",
				mediaType = "Release",
				subtitle = listOfNotNull(releaseLabel, groupNames.joinToString(", ").takeIf { it.isNotBlank() })
					.joinToString(" · ")
					.takeIf { it.isNotBlank() },
				progressText = releaseLabel,
				updatedAtText = releaseDateText,
				isBestMatch = false,
			)
		}
		return mapped.distinctBy { it.id }.withReleaseCovers()
	}

	private suspend fun List<ScrobblerContent>.withReleaseCovers(): List<ScrobblerContent> = coroutineScope {
		val seriesIds = asSequence()
			.filter { it.cover.isNullOrBlank() }
			.map { it.id }
			.distinct()
			.toList()
		if (seriesIds.isEmpty()) {
			return@coroutineScope this@withReleaseCovers
		}

		val semaphore = Semaphore(6)
		val coversById = seriesIds.map { seriesId ->
			async {
				seriesId to semaphore.withPermit { fetchSeriesCover(seriesId) }
			}
		}.awaitAll()
			.mapNotNull { (seriesId, cover) -> cover?.let { seriesId to it } }
			.toMap()

		map { content ->
			val cover = coversById[content.id]
			if (cover.isNullOrBlank()) content else content.copy(cover = cover)
		}
	}

	private suspend fun fetchSeriesCover(seriesId: Long): String? {
		return runCatching {
			val request = Request.Builder()
				.get()
				.url("$BASE_API_URL/series/$seriesId")
			okHttp.newCall(request.build()).await().parseJson()
				.optJSONObject("image")
				?.optJSONObject("url")
				?.getStringOrNull("original")
		}.getOrNull()
	}

	private suspend fun getAuthorInfo(
		id: Long,
		urlHint: String? = null,
	): ScrobblerContentInfo? {
		val authorUrl = urlHint?.takeIf { it.contains("/author/") } ?: return null
		val html = Request.Builder()
			.get()
			.url(authorUrl)
			.build()
			.let { okHttp.newCall(it).await().parseRaw() }
		val doc = Jsoup.parse(html, authorUrl)
		val title = doc.selectFirst(".tabletitle")?.text()?.trim()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
			?: return null
		val infoProperties = doc.select("div[data-cy^=info-box-]")
			.mapNotNull { section ->
				val key = section.attr("data-cy")
					.substringAfter("info-box-")
					.substringBefore("-header")
					.takeIf { section.selectFirst("b") != null }
					?.let { section.selectFirst("b")?.text()?.trim() }
					?: return@mapNotNull null
				val value = section.nextElementSibling()
					?.takeIf { it.attr("data-cy").startsWith("info-box-") }
					?.text()
					?.normalizeWhitespace()
					?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
					?: return@mapNotNull null
				key to value
			}
			.distinct()
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
			?.trim()
			?.takeIf { it.isNotBlank() }
			.orEmpty()
		val descriptionHtml = doc.selectFirst("[data-cy=info-box-comments]")
			?.html()
			?.takeIf { !Jsoup.parse(it).text().equals("N/A", ignoreCase = true) }
			.orEmpty()
		val relatedWorks = doc.select("[data-cy=author-series] a[href*=/series/]")
			.mapNotNull { link ->
				val url = link.absUrl("href").normalizeBlank() ?: return@mapNotNull null
				val titleText = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val row = link.parents().firstOrNull { it.hasClass("row") } ?: return@mapNotNull null
				val columns = row.children()
				val genre = columns.getOrNull(1)?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }
				ScrobblerContentInfo.RelatedWork(
					id = 0L,
					title = titleText,
					coverUrl = "",
					relationship = genre,
					url = url,
				)
			}
			.distinctBy { it.url }
		val tags = buildList {
			doc.select("[data-cy=info-box-genres] a").forEach { anchor ->
				anchor.text().trim().takeIf { it.isNotBlank() }?.let(::add)
			}
		}
		val actions = listOfNotNull(
			doc.selectFirst("[data-cy=info-box-social.twitter] a[href]")
				?.absUrl("href")
				?.normalizeBlank()
				?.let { twitterUrl ->
					ScrobblerContentInfo.ExternalAction(
						title = "Twitter",
						url = twitterUrl,
					)
				},
			doc.selectFirst("[data-cy=info-box-social\\.officialsite] a[href]")
				?.absUrl("href")
				?.normalizeBlank()
				?.let { siteUrl ->
					ScrobblerContentInfo.ExternalAction(
						title = "Official Website",
						url = siteUrl,
					)
				},
		)
		return ScrobblerContentInfo(
			id = id,
			name = title,
			cover = cover,
			url = authorUrl,
			descriptionHtml = descriptionHtml,
			tags = tags,
			infoboxProperties = infoProperties,
			extraSections = listOfNotNull(
				relatedWorks.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Participated Works",
						items = it,
					)
				},
			),
			actions = actions,
		)
	}

	private suspend fun parseSeriesDetails(response: JSONObject): ScrobblerContentInfo {
		val genres = mutableListOf<String>()
		response.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optJSONObject(i)?.optString("genre")?.takeIf { it.isNotBlank() }?.let(genres::add)
			}
		}

		val categories = mutableListOf<String>()
		response.optJSONArray("categories")?.let { arr ->
			for (i in 0 until minOf(arr.length(), 10)) {
				arr.optJSONObject(i)?.optString("category")?.takeIf { it.isNotBlank() }?.let(categories::add)
			}
		}

		val authors = mutableListOf<String>()
		val staff = mutableListOf<ScrobblerContentInfo.PersonInfo>()
		response.optJSONArray("authors")?.let { arr ->
			for (i in 0 until arr.length()) {
				val authorObj = arr.optJSONObject(i) ?: continue
				val name = authorObj.optString("name").takeIf { it.isNotBlank() } ?: continue
				val type = authorObj.optString("type").takeIf { it.isNotBlank() }
				authors.add(if (type != null) "$name ($type)" else name)
				val id = authorObj.optLong("author_id", 0L).takeIf { it > 0L }
					?: authorObj.optLong("id", 0L).takeIf { it > 0L }
				staff.add(
					ScrobblerContentInfo.PersonInfo(
						id = id,
						name = name,
						url = authorObj.getStringOrNull("url"),
						role = type,
					),
				)
			}
		}

		val relatedWorks = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		response.optJSONArray("related_series")?.let { arr ->
			for (i in 0 until arr.length()) {
				val rel = arr.optJSONObject(i) ?: continue
				val relId = rel.optLong("related_series_id", 0L)
				val relTitle = rel.optString("related_series_name").takeIf { it.isNotBlank() } ?: continue
				if (relId > 0L) {
					relatedWorks.add(ScrobblerContentInfo.RelatedWork(
						id = relId,
						title = relTitle,
						coverUrl = "",
						relationship = rel.optString("relation_type").takeIf { it.isNotBlank() },
						url = "https://www.mangaupdates.com/series/${relId}",
					))
				}
			}
		}

		val recommendations = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		response.optJSONArray("recommendations")?.let { arr ->
			for (i in 0 until minOf(arr.length(), 10)) {
				val rec = arr.optJSONObject(i) ?: continue
				val recId = rec.optLong("series_id", 0L)
				val recTitle = rec.optString("series_name").takeIf { it.isNotBlank() } ?: continue
				val recCover = rec.optJSONObject("series_image")?.optJSONObject("url")?.getStringOrNull("original")
				if (recId > 0L) {
					recommendations.add(ScrobblerContentInfo.RelatedWork(
						id = recId,
						title = recTitle,
						coverUrl = recCover.orEmpty(),
						url = "https://www.mangaupdates.com/series/${recId}",
					))
				}
			}
		}

		val infoboxProperties = mutableListOf<Pair<String, String>>()
		response.optString("type").takeIf { it.isNotBlank() }?.let { infoboxProperties.add("Type" to it) }
		response.optString("year").takeIf { it.isNotBlank() }?.let { infoboxProperties.add("Year" to it) }
		response.optString("status").takeIf { it.isNotBlank() }?.let { infoboxProperties.add("Status" to it) }
		val rating = response.optDouble("bayesian_rating", 0.0)
		if (rating > 0) infoboxProperties.add("Rating" to String.format("%.2f", rating))
		val contentUrl = response.getString("url")
		val supplemental = fetchSupplementalDetails(contentUrl)

		return ScrobblerContentInfo(
			id = response.getLong("series_id"),
			name = response.getString("title"),
			cover = response.optJSONObject("image")?.optJSONObject("url")?.getString("original").orEmpty(),
			url = contentUrl,
			descriptionHtml = response.optString("description", "").replace("\n", "<br>"),
			tags = genres + categories,
			authors = authors,
			staff = staff.distinctBy { it.id ?: it.name },
			infoboxProperties = infoboxProperties,
			commentThreads = supplemental.commentThreads,
			reviews = supplemental.reviews,
			relatedWorks = relatedWorks,
			recommendations = recommendations,
			actions = supplemental.actions,
		)
	}

	private suspend fun fetchSupplementalDetails(contentUrl: String): MangaUpdatesSupplementalPayload {
		return runCatching {
			val html = Request.Builder()
				.get()
				.url(contentUrl)
				.build()
				.let { okHttp.newCall(it).await().parseRaw() }
			val doc = Jsoup.parse(html, contentUrl)
			val commentThreads = parseSeriesComments(doc)
			val reviewLinks = parseReviewLinks(doc)
			val reviews = reviewLinks.take(REVIEW_PAGE_SIZE).mapNotNull { reviewUrl ->
				runCatching {
					fetchReviewEntry(reviewUrl)
				}.getOrNull()
			}
			val actions = buildList {
				if (commentThreads.isNotEmpty()) {
					add(
						ScrobblerContentInfo.ExternalAction(
							title = "Comments",
							url = "${contentUrl.trimEnd('/') }#comments",
						),
					)
				}
				if (reviews.isNotEmpty() || reviewLinks.isNotEmpty()) {
					add(
						ScrobblerContentInfo.ExternalAction(
							title = "Reviews",
							url = reviewLinks.firstOrNull() ?: contentUrl,
						),
					)
				}
			}
			MangaUpdatesSupplementalPayload(
				commentThreads = commentThreads,
				reviews = reviews,
				actions = actions,
			)
		}.getOrElse { MangaUpdatesSupplementalPayload() }
	}

	private fun parseSeriesComments(doc: Document): List<ScrobblerContentInfo.CommentThread> {
		return doc.select("div[data-cy=comment-row]").take(COMMENTS_PAGE_SIZE).mapNotNull { row ->
			val body = row.nextElementSibling()?.takeIf { it.selectFirst(".mu-markdown-module___SC9hG__mu_markdown") != null }
				?: return@mapNotNull null
			val title = row.selectFirst(".comment_title")?.text()?.trim().orEmpty()
			val authorLink = row.selectFirst("a[href*=/member/]")
			val authorName = authorLink?.text()?.trim().orEmpty().ifBlank { "MangaUpdates User" }
			val avatarUrl = row.selectFirst("img[alt=user avatar]")?.absUrl("src").normalizeBlank()
			val rating = row.selectFirst(".comment_rating")?.text()?.extractFractionalNumber()
			val postedAt = row.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }?.take(10)
			val content = body.selectFirst(".mu-markdown-module___SC9hG__mu_markdown")
				?.html()
				?.toPlainText()
				?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			ScrobblerContentInfo.CommentThread(
				id = row.selectFirst("a[id^=comment]")?.id()?.removePrefix("comment").orEmpty().ifBlank { title },
				userName = authorName,
				userUrl = authorLink?.absUrl("href").normalizeBlank(),
				avatarUrl = avatarUrl,
				rating = rating,
				status = title.takeIf { it.isNotBlank() && !it.equals("No Subject", ignoreCase = true) },
				postedAt = postedAt,
				content = content,
			)
		}
	}

	private fun parseReviewLinks(doc: Document): List<String> {
		val header = doc.select("div[data-cy=info-box-unknown-header] b").firstOrNull {
			it.text().contains("User Reviews", ignoreCase = true)
		} ?: return emptyList()
		val content = header.parent()?.nextElementSibling() ?: return emptyList()
		return content.select("a[href^=/review/], a[href*=/review/]")
			.mapNotNull { anchor -> anchor.absUrl("href").normalizeBlank() }
			.distinct()
	}

	private suspend fun fetchReviewEntry(reviewUrl: String): ScrobblerContentInfo.ReviewEntry? {
		val html = Request.Builder()
			.get()
			.url(reviewUrl)
			.build()
			.let { okHttp.newCall(it).await().parseRaw() }
		val doc = Jsoup.parse(html, reviewUrl)
		val excerpt = doc.selectFirst(".mu-markdown-module___SC9hG__mu_markdown")
			?.html()
			?.toPlainText()
			?.takeIf { it.isNotBlank() }
			?: return null
		val metadata = doc.select("div.p-1.col-12.text").firstOrNull { element ->
			element.text().contains("by") && element.selectFirst("time[datetime]") != null
		}
		val authorLink = metadata?.selectFirst("a[href*=/member/]")
		val authorName = authorLink?.text()?.trim()
			?: metadata?.selectFirst("[class*=review-by-id-module__][class*=newsname]")?.text()?.trim()
			?: metadata?.text()
				?.substringAfter("by", missingDelimiterValue = "")
				?.substringBefore(" on", missingDelimiterValue = "")
				?.trim()
				?.takeIf { it.isNotBlank() }
			?: "MangaUpdates User"
		val postedAt = doc.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }?.take(10)
		val rating = doc.selectFirst(".specialtext b")?.text()?.extractFractionalNumber()
		val commentsCount = doc.select("div[data-cy=comment-row]").size.takeIf { it > 0 }
		return ScrobblerContentInfo.ReviewEntry(
			id = reviewUrl.substringAfterLast('/'),
			title = excerpt.toReviewTitle(),
			authorName = authorName,
			authorUrl = authorLink?.absUrl("href").normalizeBlank(),
			avatarUrl = null,
			postedAt = postedAt,
			excerpt = buildString {
				rating?.let {
					append("Rating ")
					append(String.format("%.1f/10", it))
					append(" · ")
				}
				append(excerpt)
			},
			url = reviewUrl,
			repliesCount = commentsCount,
		)
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		Log.d(TAG, "createRate: mangaId=$mangaId, targetId=$scrobblerContentId, title=${content.name}")
		val lines = listOf(
			"[",
			"  {",
			"    \"series\": {",
			"      \"id\": $scrobblerContentId",
			"    },",
			"    \"list_id\": 0,",
			"    \"status\": {",
			"      \"chapter\": 0",
			"    }",
			"  }",
			"]",
		)
		val payloadStr = lines.joinToString("\n")

		val request = Request.Builder()
			.post(payloadStr.toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/lists/series")

		val response = okHttp.newCall(request.build()).await()
		if (response.isSuccessful) {
			Log.d(TAG, "createRate: success, targetId=$scrobblerContentId")
			val entity = ScrobblingEntity(
				scrobbler = ScrobblerService.MANGAUPDATES.id,
				id = scrobblerContentId.toInt(),
				mangaId = mangaId,
				targetId = scrobblerContentId,
				status = "0",
				chapter = 0,
				comment = null,
				rating = 0f
			)
			db.upsertScrobblingForManga(entity, workResolver, mangaId = mangaId)
		} else {
			val responseBodyStr = response.body?.string() ?: "Empty body"
			Log.e(TAG, "createRate: FAILED code=${response.code}, body=${responseBodyStr.take(200)}")
			throw IOException("Failed to create rate: ${response.code}, body: $responseBodyStr")
		}
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.MANGAUPDATES.id, mangaId, workResolver)
		if (entity == null) {
			Log.w(TAG, "updateRate(chapter): no entity for mangaId=$mangaId, skipping")
			return
		}
		Log.d(TAG, "updateRate(chapter): rateId=$rateId, mangaId=$mangaId, chapter=$chapter, status=${entity.status}")
			
		val payload = JSONArray().apply {
			put(JSONObject().apply {
				put("series", JSONObject().apply { put("id", rateId) })
				put("list_id", entity.status?.toLongOrNull() ?: 0L)
				put("status", JSONObject().apply { put("chapter", chapter) })
			})
		}

		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/lists/series/update")

		val response = okHttp.newCall(request.build()).await()
		if (response.isSuccessful) {
			Log.d(TAG, "updateRate(chapter): success")
		} else {
			Log.e(TAG, "updateRate(chapter): FAILED code=${response.code}")
		}

		val updated = entity.copy(chapter = chapter)
		db.upsertScrobblingForManga(updated, workResolver, mangaId = mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.MANGAUPDATES.id, mangaId, workResolver)
			?: return

		val payload = JSONArray().apply {
			put(JSONObject().apply {
				put("series", JSONObject().apply { put("id", rateId) })
				put("list_id", status?.toLongOrNull() ?: 0L)
				put("status", JSONObject().apply { put("chapter", entity.chapter) })
			})
		}

		val request = Request.Builder()
			.post(payload.toString().toRequestBody(CONTENT_TYPE))
			.url("$BASE_API_URL/lists/series/update")

		okHttp.newCall(request.build()).await().use { response ->
			if (!response.isSuccessful) {
				throw IOException("Failed to update MangaUpdates list entry: HTTP ${response.code}")
			}
		}

		val remoteRating = toMangaUpdatesRating(rating)
		if (remoteRating > 0) {
			val scorePayload = JSONObject().apply {
				put("rating", remoteRating)
			}
			val ratingRequest = Request.Builder()
				.put(scorePayload.toString().toRequestBody(CONTENT_TYPE))
				.url("$BASE_API_URL/series/$rateId/rating")
			okHttp.newCall(ratingRequest.build()).await().use { response ->
				if (!response.isSuccessful) {
					throw IOException("Failed to update MangaUpdates rating: HTTP ${response.code}")
				}
			}
		} else {
			val ratingRequest = Request.Builder()
				.delete()
				.url("$BASE_API_URL/series/$rateId/rating")
			okHttp.newCall(ratingRequest.build()).await().use { response ->
				if (!response.isSuccessful && response.code != 404) {
					throw IOException("Failed to delete MangaUpdates rating: HTTP ${response.code}")
				}
			}
		}

		val updated = entity.copy(status = status, rating = rating, comment = comment)
		db.upsertScrobblingForManga(updated, workResolver, mangaId = mangaId)
	}

	suspend fun syncLibraryFromRemote(): Int {
		Log.d(TAG, "syncLibrary: start fetching remote lists")
		val existingEntries = buildExistingEntriesByTargetId()
		Log.d(TAG, "syncLibrary: existingEntries.size=${existingEntries.size}")

		val lists = try {
			fetchUserLists()
		} catch (e: Exception) {
			Log.e(TAG, "syncLibrary: failed to fetch user lists", e)
			return -1
		}

		if (lists.isEmpty()) {
			Log.d(TAG, "syncLibrary: no lists returned, skipping sync")
			return 0
		}

		Log.d(TAG, "syncLibrary: fetched ${lists.size} lists: ${lists.map { "${it.first}(${it.second})" }}")

		val synced = ArrayList<ScrobblingEntity>()
		for ((listId, listStatus) in lists) {
			try {
				val entries = fetchListSeries(listId, listStatus, existingEntries)
				synced.addAll(entries)
				Log.d(TAG, "syncLibrary: listId=$listId status=$listStatus fetched ${entries.size} entries")
			} catch (e: Exception) {
				Log.e(TAG, "syncLibrary: failed to fetch listId=$listId", e)
			}
		}

		val hydratedEntries = hydrateUserRatings(synced)

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.MANGAUPDATES.id)
			hydratedEntries.forEach { entity ->
				db.upsertScrobbling(entity, workResolver)
			}
		}

		Log.d(TAG, "syncLibrary: completed, synced=${hydratedEntries.size}")
		return hydratedEntries.size
	}

	private suspend fun buildExistingEntriesByTargetId(): Map<Long, ScrobblingEntity> {
		val existing = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.MANGAUPDATES.id)
		val mappings = LinkedHashMap<Long, ScrobblingEntity>(existing.size)
		for (entity in existing) {
			val targetId = entity.targetId
			if (targetId == 0L) continue
			val old = mappings[targetId]
			if (old == null || shouldPreferExistingEntry(candidate = entity, current = old)) {
				mappings[targetId] = entity
			}
		}
		return mappings
	}

	private fun shouldPreferExistingEntry(candidate: ScrobblingEntity, current: ScrobblingEntity): Boolean {
		return existingEntryScore(candidate) > existingEntryScore(current)
	}

	private fun existingEntryScore(entity: ScrobblingEntity): Int {
		var score = 0
		if (entity.mangaId != 0L) score += 8
		if (entity.rating > 0f) score += 4
		if (!entity.comment.isNullOrBlank()) score += 2
		if (entity.chapter > 0) score += 1
		return score
	}

	private suspend fun fetchUserLists(): List<Pair<Long, String>> {
		val request = Request.Builder()
			.get()
			.url("$BASE_API_URL/lists")

		val response = okHttp.newCall(request.build()).await()
		if (!response.isSuccessful) {
			Log.e(TAG, "fetchUserLists: HTTP ${response.code}")
			throw IOException("Failed to fetch lists: ${response.code}")
		}

		val results = response.parseJsonArray()
		Log.d(TAG, "fetchUserLists: raw array length=${results.length()}")
		if (results.length() == 0) return emptyList()

		val lists = mutableListOf<Pair<Long, String>>()
		for (i in 0 until results.length()) {
			val item = results.getJSONObject(i)
			val listId = item.optLong("list_id", -1L)
			val listType = item.optString("type", "").lowercase()
			val listTitle = item.optString("title", "")
			Log.d(TAG, "fetchUserLists: found list listId=$listId, type=$listType, title=$listTitle")
			if (listId < 0) continue

			val status = when {
				listType == "read" -> "0"
				listType == "wish" -> "1"
				listType == "complete" -> "2"
				listType == "unfinished" -> "3"
				listType == "hold" -> "4"
				else -> listId.toString()
			}
			lists.add(listId to status)
		}
		return lists
	}

	private suspend fun fetchListSeries(
		listId: Long,
		listStatus: String,
		existingEntries: Map<Long, ScrobblingEntity>,
	): List<ScrobblingEntity> {
		val synced = ArrayList<ScrobblingEntity>()
		var page = 1
		var hasMore = true

		while (hasMore) {
			val payload = JSONObject().apply {
				put("page", page)
				put("perpage", 100)
			}
			Log.d(TAG, "fetchListSeries: POST /lists/$listId/search page=$page")

			val request = Request.Builder()
				.post(payload.toString().toRequestBody(CONTENT_TYPE))
				.url("$BASE_API_URL/lists/$listId/search")

			val callResponse = okHttp.newCall(request.build()).await()
			if (!callResponse.isSuccessful) {
				val bodySnippet = runCatching { callResponse.body?.string()?.take(200) }.getOrNull() ?: ""
				Log.w(TAG, "fetchListSeries: HTTP ${callResponse.code} body=$bodySnippet")
				break
			}

			val json = callResponse.parseJson()
			val results = json.optJSONArray("results")
			if (results == null || results.length() == 0) {
				hasMore = false
				break
			}

			val perPage = json.optInt("per_page", 100)
			val totalHits = json.optLong("total_hits", 0L)

			for (i in 0 until results.length()) {
				val result = results.getJSONObject(i)
				// Try nested structure: result.record.series.{id, title, url}
				val record = result.optJSONObject("record")
				val series = record?.optJSONObject("series")
				val targetId = series?.optLong("id", 0L)?.takeIf { it > 0L }
					?: result.optLong("series_id", 0L).takeIf { it > 0L }
				if (targetId == null || targetId == 0L) continue

				val status = record?.optJSONObject("status")
				val chapter = status?.optInt("chapter", 0)
					?: result.optInt("chapter", 0)

				val seriesTitle = series?.optString("title")?.takeIf { it.isNotBlank() }
					?: result.optString("series_title").takeIf { it.isNotBlank() }
				val seriesUrl = series?.optString("url")?.takeIf { it.isNotBlank() }
					?: result.optString("series_url").takeIf { it.isNotBlank() }

				val existing = existingEntries[targetId]
				val mangaId = existing?.mangaId ?: 0L

				val entity = ScrobblingEntity(
					scrobbler = ScrobblerService.MANGAUPDATES.id,
					id = targetId.toInt(),
					mangaId = mangaId,
					targetId = targetId,
					status = listStatus,
					chapter = chapter,
					comment = existing?.comment,
					rating = existing?.rating ?: 0f,
					mediaType = existing?.mediaType ?: "",
					remoteTitle = seriesTitle ?: existing?.remoteTitle,
					remoteCoverUrl = existing?.remoteCoverUrl,
					remoteUrl = seriesUrl ?: existing?.remoteUrl,
				)
				Log.d(TAG, "fetchListSeries: targetId=$targetId, title=${entity.remoteTitle}, coverUrl=${entity.remoteCoverUrl}")
				synced.add(entity)
			}

			val totalPages = if (perPage > 0) (totalHits + perPage - 1) / perPage else 1
			hasMore = page < totalPages
			page++
			Log.d(TAG, "fetchListSeries: listId=$listId page=${page - 1}/$totalPages, hasMore=$hasMore, synced=${synced.size}")
		}

		return synced
	}

	private suspend fun hydrateUserRatings(entities: List<ScrobblingEntity>): List<ScrobblingEntity> = coroutineScope {
		val targetIds = entities.asSequence()
			.map { it.targetId }
			.filter { it > 0L }
			.distinct()
			.toList()
		if (targetIds.isEmpty()) {
			return@coroutineScope entities
		}

		val semaphore = Semaphore(USER_RATING_SYNC_CONCURRENCY)
		val ratingsByTargetId = targetIds.map { targetId ->
			async {
				targetId to semaphore.withPermit { fetchUserSeriesRating(targetId) }
			}
		}.awaitAll().toMap()

		entities.map { entity ->
			val remoteRating = ratingsByTargetId[entity.targetId]
			if (remoteRating == null || remoteRating == entity.rating) {
				entity
			} else {
				entity.copy(rating = remoteRating)
			}
		}
	}

	private suspend fun fetchUserSeriesRating(targetId: Long): Float? {
		return runCatching {
			val request = Request.Builder()
				.get()
				.url("$BASE_API_URL/series/$targetId/rating")
			val response = okHttp.newCall(request.build()).await()
			if (response.code == 404) {
				Log.d(TAG, "fetchUserSeriesRating: targetId=$targetId returned 404, keeping local rating")
				return null
			}
			if (!response.isSuccessful) {
				Log.w(TAG, "fetchUserSeriesRating: targetId=$targetId HTTP ${response.code}")
				return null
			}
			val json = response.parseJson()
			parseMangaUpdatesUserRating(json)?.also { rating ->
				Log.d(TAG, "fetchUserSeriesRating: targetId=$targetId rating=$rating")
			}
		}.getOrElse { error ->
			Log.w(TAG, "fetchUserSeriesRating: targetId=$targetId failed", error)
			null
		}
	}

	suspend fun persistRemoteCoverIfMissing(targetId: Long): Boolean {
		if (targetId <= 0L) return false
		val entities = db.getScrobblingDao().findAllByTargetId(ScrobblerService.MANGAUPDATES.id, targetId)
		val preferred = entities.preferredScrobblingEntity()
		if (preferred == null || entities.all { !it.remoteCoverUrl.isNullOrBlank() }) {
			return false
		}
		val coverUrl = fetchSeriesCover(targetId)?.takeIf { it.isNotBlank() } ?: return false
		entities.forEach { entity ->
			if (entity.remoteCoverUrl.isNullOrBlank()) {
				db.upsertScrobblingPreview(entity, workResolver, coverUrl = coverUrl)
			}
		}
		Log.d(TAG, "persistRemoteCoverIfMissing: targetId=$targetId ownerMangaId=${preferred.mangaId} updated=${entities.size}")
		return true
	}

	private fun String.toPlainText(): String {
		return Jsoup.parse(this).text().normalizeWhitespace()
	}

	private fun String.normalizeWhitespace(): String {
		return replace(Regex("\\s+"), " ").trim()
	}

	private fun String.toReviewTitle(): String {
		return lineSequence()
			.map { it.trim() }
			.firstOrNull { it.isNotBlank() }
			?.take(72)
			?.takeIf { it.isNotBlank() }
			?: "Review"
	}

	private fun String?.normalizeBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }

	private fun String.extractFractionalNumber(): Float? {
		val match = Regex("""(\d+(?:\.\d+)?)""").find(this) ?: return null
		return match.groupValues.getOrNull(1)?.toFloatOrNull()
	}

	private data class MangaUpdatesSupplementalPayload(
		val commentThreads: List<ScrobblerContentInfo.CommentThread> = emptyList(),
		val reviews: List<ScrobblerContentInfo.ReviewEntry> = emptyList(),
		val actions: List<ScrobblerContentInfo.ExternalAction> = emptyList(),
	)
}
