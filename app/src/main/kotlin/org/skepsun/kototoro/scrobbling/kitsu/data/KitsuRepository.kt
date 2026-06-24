package org.skepsun.kototoro.scrobbling.kitsu.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.util.ext.parseJsonOrNull
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getFloatOrDefault
import org.skepsun.kototoro.parsers.util.json.getIntOrDefault
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.preferredMangaMappingByTargetId
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
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuInterceptor.Companion.VND_JSON
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.work.domain.WorkResolver
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.LinkedHashMap

private const val BASE_WEB_URL = "https://kitsu.app"
private const val DISCOVERY_PAGE_LIMIT = 20
private const val DAILY_SCHEDULE_CACHE_SIZE = 14

class KitsuRepository(
	@ApplicationContext context: Context,
	private val okHttp: OkHttpClient,
	private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

	// not in use yet
	private val clientId = context.getString(R.string.kitsu_clientId)
	private val clientSecret = context.getString(R.string.kitsu_clientSecret)

	override val oauthUrl: String = "kototoro+kitsu://auth"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	private data class DailyScheduleCacheEntry(
		val items: MutableList<ScrobblerContent> = ArrayList(),
		var nextOffset: Int = 0,
		var total: Int = Int.MAX_VALUE,
		var exhausted: Boolean = false,
	)

	private val dailyScheduleCacheMutex = Mutex()
	private val dailyScheduleCache = object : LinkedHashMap<LocalDate, DailyScheduleCacheEntry>(
		DAILY_SCHEDULE_CACHE_SIZE,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<LocalDate, DailyScheduleCacheEntry>?,
		): Boolean {
			return size > DAILY_SCHEDULE_CACHE_SIZE
		}
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		if (code != null) {
			body.add("grant_type", "password")
			body.add("username", code.substringBefore(';'))
			body.add("password", code.substringAfter(';'))
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken))
		}
		val request = Request.Builder()
			.post(body.build())
			.url("$BASE_WEB_URL/api/oauth/token")
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
			.url("$BASE_WEB_URL/api/edge/users?filter[self]=true")
		val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
			.getJSONArray("data")
			.getJSONObject(0)
		val attrs = response.getJSONObject("attributes")
		val user = ScrobblerUser(
			id = response.getAsLong("id"),
			nickname = attrs.getString("name"),
			avatar = attrs.optJSONObject("avatar")?.getStringOrNull("small"),
			service = ScrobblerService.KITSU,
		).also { storage.user = it }
		val stats = runCatching {
			loadStats(user.id)
		}.getOrNull()
		return ScrobblerUserProfile(
			user = user,
			stats = stats,
		)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		return db.deleteScrobblingByWorkOrManga(ScrobblerService.KITSU.id, mangaId, workResolver)
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val type = if (isAnime) "anime" else "manga"
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/$type?page[limit]=20&page[offset]=$offset&filter[text]=${query.urlEncoded()}")
		val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
		return response.getJSONArray("data").mapJSON { jo ->
			val attrs = jo.getJSONObject("attributes")
			val content = createDiscoveryContent(jo, attrs, type)
			content.copy(
				isBestMatch = sequenceOf(content.name, content.primaryTitle, content.secondaryTitle, content.altName)
					.filterNotNull()
					.any { it.equals(query, ignoreCase = true) },
			)
		}
	}

	// ── Discovery API (public, no auth) ─────────────────────────

	/**
	 * Fetch trending anime or manga.
	 * @param mediaType "anime" or "manga"
	 */
	suspend fun getTrending(mediaType: String): List<ScrobblerContent> {
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/trending/$mediaType")
		return parseMediaList(okHttp.newCall(request.build()).await().parseJson(), mediaType)
	}

	/**
	 * Browse anime/manga by category slug, sorted by popularity (descending userCount).
	 * @param mediaType "anime" or "manga"
	 * @param categorySlug e.g. "action", "romance", "ecchi", or null for all
	 * @param page 1-based page number
	 */
	suspend fun getRankings(
		mediaType: String,
		categorySlug: String?,
		page: Int,
		sort: String = "-userCount",
	): List<ScrobblerContent> {
		val limit = DISCOVERY_PAGE_LIMIT
		val offset = (page - 1) * limit
		val urlBuilder = StringBuilder("$BASE_WEB_URL/api/edge/$mediaType?page[limit]=$limit&page[offset]=$offset&sort=${sort.urlEncoded()}")
		if (!categorySlug.isNullOrBlank()) {
			urlBuilder.append("&filter[categories]=${categorySlug.urlEncoded()}")
		}
		val request = Request.Builder()
			.get()
			.url(urlBuilder.toString())
		return parseMediaList(okHttp.newCall(request.build()).await().parseJson().ensureSuccess(), mediaType)
	}

	/**
	 * Kitsu does not expose a calendar filter. Build the daily schedule from current anime:
	 * prefer nextRelease weekday and fall back to startDate weekday when nextRelease is absent.
	 */
	suspend fun getDailySchedule(date: LocalDate, page: Int): List<ScrobblerContent> {
		val requiredCount = (page + 1) * DISCOVERY_PAGE_LIMIT
		return dailyScheduleCacheMutex.withLock {
			val entry = dailyScheduleCache.getOrPut(date) { DailyScheduleCacheEntry() }
			val targetDay = date.dayOfWeek
			val seenIds = entry.items.mapTo(HashSet()) { it.id }

			while (entry.items.size < requiredCount && !entry.exhausted && entry.nextOffset < entry.total) {
				val request = Request.Builder()
					.get()
					.url(
						"$BASE_WEB_URL/api/edge/anime?page[limit]=$DISCOVERY_PAGE_LIMIT" +
							"&page[offset]=${entry.nextOffset}&filter[status]=current&sort=-userCount"
					)
				val json = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
				entry.total = json.optJSONObject("meta")?.optInt("count", entry.total) ?: entry.total
				val data = json.optJSONArray("data")
				if (data == null || data.length() == 0) {
					entry.exhausted = true
					break
				}

				data.mapJSON { item ->
					val attrs = item.getJSONObject("attributes")
					val releaseDate = attrs.getStringOrNull("nextRelease")?.toKitsuReleaseDate()
					val startDate = attrs.getStringOrNull("startDate")?.toKitsuStartDate()
					val scheduleDay = releaseDate?.dayOfWeek ?: startDate?.dayOfWeek
					if (scheduleDay == targetDay) {
						val content = createDiscoveryContent(item, attrs, "anime")
						if (seenIds.add(content.id)) {
							entry.items += content
						}
					}
				}
				entry.nextOffset += DISCOVERY_PAGE_LIMIT
				if (entry.nextOffset >= entry.total) {
					entry.exhausted = true
				}
			}

			entry.items
				.drop(page * DISCOVERY_PAGE_LIMIT)
				.take(DISCOVERY_PAGE_LIMIT)
		}
	}

	/**
	 * Search anime by text query (similar to [findContent] but for anime).
	 */
	suspend fun findAnime(query: String, offset: Int): List<ScrobblerContent> {
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/anime?page[limit]=20&page[offset]=$offset&filter[text]=${query.urlEncoded()}")
		return parseMediaList(okHttp.newCall(request.build()).await().parseJson().ensureSuccess(), "anime")
	}

	private fun parseMediaList(json: JSONObject, mediaType: String): List<ScrobblerContent> {
		return json.getJSONArray("data").mapJSON { jo ->
			val attrs = jo.getJSONObject("attributes")
			createDiscoveryContent(jo, attrs, mediaType)
		}
	}

	private fun createDiscoveryContent(
		entry: JSONObject,
		attrs: JSONObject,
		mediaType: String,
	): ScrobblerContent {
		val titles = attrs.optJSONObject("titles")
		val canonicalTitle = attrs.optString("canonicalTitle", "")
		val primaryTitle = titles?.getStringOrNull("ja_jp")
			?: titles?.getStringOrNull("ja_jp_ro")
			?: canonicalTitle.takeIf { it.isNotBlank() }
		val secondaryTitle = sequenceOf(
			canonicalTitle,
			titles?.getStringOrNull("en_jp"),
			titles?.getStringOrNull("en_us"),
		).filterNotNull().firstOrNull { !it.equals(primaryTitle, ignoreCase = true) }
		val posterImage = attrs.optJSONObject("posterImage")
		val subtype = attrs.optString("subtype").takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
		val status = attrs.getStringOrNull("status")?.replace("_", " ")
		return ScrobblerContent(
			id = entry.getAsLong("id"),
			name = canonicalTitle.ifBlank { primaryTitle.orEmpty() },
			altName = primaryTitle ?: secondaryTitle,
			cover = posterImage?.getStringOrNull("small") ?: posterImage?.getStringOrNull("medium").orEmpty(),
			url = "$BASE_WEB_URL/$mediaType/${attrs.getString("slug")}",
			mediaType = subtype,
			primaryTitle = primaryTitle ?: canonicalTitle,
			secondaryTitle = secondaryTitle,
			subtitle = listOfNotNull(
				subtype,
				status,
			).joinToString(" · ").ifBlank { null },
			progressText = if (mediaType == "anime") {
				attrs.optInt("episodeCount", 0).takeIf { it > 0 }?.let { "EP $it" }
			} else {
				attrs.optInt("chapterCount", 0).takeIf { it > 0 }?.let { "CH $it" }
			},
			updatedAtText = attrs.getStringOrNull("updatedAt")?.takeIf { it.isNotBlank() }?.take(10),
			score = attrs.getStringOrNull("averageRating")?.toFloatOrNull(),
			scoreMax = 100f,
				totalEpisodes = attrs.optInt("episodeCount", 0).takeIf { it > 0 } ?: attrs.optInt("chapterCount", 0).takeIf { it > 0 },
		)
	}

	private fun String.toKitsuReleaseDate(): LocalDate? {
		return runCatching {
			OffsetDateTime.parse(this)
				.atZoneSameInstant(ZoneId.systemDefault())
				.toLocalDate()
		}.getOrNull()
	}

	private fun String.toKitsuStartDate(): LocalDate? {
		return runCatching { LocalDate.parse(this) }.getOrNull()
	}
	
	private suspend fun isAnimeContent(mangaId: Long): Boolean {
		val mangaItem = db.getMangaDao().find(mangaId) ?: return false
		if (mangaItem.manga.url.startsWith("file://") && (mangaItem.manga.url.contains("/video/") || arrayOf(".mp4", ".mkv", ".webm", ".ts", ".avi", ".m3u8").any { mangaItem.manga.url.endsWith(it, ignoreCase = true) })) {
			return true
		}
		val source = org.skepsun.kototoro.core.model.ContentSource(mangaItem.manga.source)
		val contentType = source.getContentType()
		return contentType == org.skepsun.kototoro.parsers.model.ContentType.VIDEO || contentType == org.skepsun.kototoro.parsers.model.ContentType.HENTAI_VIDEO
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val isAnime = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.KITSU.id)
			.firstOrNull { it.targetId == id }?.let { isAnimeContent(it.mangaId) } ?: false
		return getContentInfo(id, isAnime)
	}

	suspend fun getContentInfo(id: Long, mangaId: Long): ScrobblerContentInfo {
		return getContentInfo(id, isAnimeContent(mangaId))
	}

	private suspend fun getContentInfo(id: Long, isAnime: Boolean): ScrobblerContentInfo {
		val firstType = if (isAnime) "anime" else "manga"
		val secondType = if (isAnime) "manga" else "anime"

		val (data, mediaType) = try {
			val req = Request.Builder().get()
				.url("$BASE_WEB_URL/api/edge/$firstType/$id?include=categories,mediaRelationships.destination")
			val json = okHttp.newCall(req.build()).await().parseJson().ensureSuccess()
			json to firstType
		} catch (_: Exception) {
			val req = Request.Builder().get()
				.url("$BASE_WEB_URL/api/edge/$secondType/$id?include=categories,mediaRelationships.destination")
			val json = okHttp.newCall(req.build()).await().parseJson().ensureSuccess()
			json to secondType
		}

		return parseContentInfoResponse(data, mediaType)
	}

	suspend fun getAnimeInfo(id: Long): ScrobblerContentInfo {
		val req = Request.Builder().get()
			.url("$BASE_WEB_URL/api/edge/anime/$id?include=categories,mediaRelationships.destination")
		val json = okHttp.newCall(req.build()).await().parseJson().ensureSuccess()
		return parseContentInfoResponse(json, "anime")
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
		page: Int,
		limit: Int = 10,
	): List<ScrobblerContent> {
		if (entityType != EntityType.CHARACTER) {
			return emptyList()
		}
		val offset = page.coerceAtLeast(0) * limit
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/characters?filter[name]=${query.urlEncoded()}&page[limit]=$limit&page[offset]=$offset")
		val data = okHttp.newCall(request.build()).await().parseJson().ensureSuccess().optJSONArray("data")
			?: return emptyList()
		return data.mapJSON { item ->
			val id = item.optString("id").toLongOrNull() ?: 0L
			val attrs = item.optJSONObject("attributes")
			val name = item.displayEntityName()
			val slug = attrs?.getStringOrNull("slug")
			ScrobblerContent(
				id = id,
				name = name,
				altName = attrs?.optJSONObject("names")?.getStringOrNull("ja_jp")?.takeIf {
					!it.equals(name, ignoreCase = true)
				},
				cover = attrs?.optJSONObject("image")?.bestKitsuImage(),
				url = "$BASE_WEB_URL/characters/${slug ?: id}",
			)
		}.filter { it.id > 0L }
	}

	private suspend fun parseContentInfoResponse(data: JSONObject, mediaType: String): ScrobblerContentInfo {

		val mainData = data.getJSONObject("data")
		val id = mainData.getAsLong("id")
		val attrs = mainData.getJSONObject("attributes")
		val included = data.optJSONArray("included")
		val discussion = fetchDiscussionPayload(mediaType, id)

		// --- Basic info ---
		val canonicalTitle = attrs.optString("canonicalTitle", "")
		val titlesObj = attrs.optJSONObject("titles")
		val slug = attrs.optString("slug", "")
		val synopsis = attrs.optString("synopsis", "").replace("\\n", "<br>")
		val posterImage = attrs.optJSONObject("posterImage")
		val cover = posterImage?.getStringOrNull("large")
			?: posterImage?.getStringOrNull("medium").orEmpty()

		// --- Infobox properties ---
		val infobox = mutableListOf<Pair<String, String>>()

		// Titles
		titlesObj?.let { titles ->
			titles.getStringOrNull("ja_jp")?.let { infobox.add("日语" to it) }
			titles.getStringOrNull("en_jp")?.let { infobox.add("日语 (Romaji)" to it) }
			titles.getStringOrNull("en")?.let { infobox.add("英语" to it) }
		}

		// Type
		attrs.getStringOrNull("subtype")?.let { subtype ->
			infobox.add("类型" to subtype.replaceFirstChar { it.uppercase() })
		}

		// Status
		attrs.getStringOrNull("status")?.let { status ->
			val statusLabel = when (status) {
				"current" -> "正在播出"
				"finished" -> "已完结"
				"tba" -> "待定"
				"unreleased" -> "未播出"
				"upcoming" -> "即将播出"
				else -> status
			}
			infobox.add("状态" to statusLabel)
		}

		// Dates
		attrs.getStringOrNull("startDate")?.let { infobox.add("开始日期" to it) }
		attrs.getStringOrNull("endDate")?.let { infobox.add("结束日期" to it) }

		// Age rating
		val ageRating = attrs.getStringOrNull("ageRating")
		val ageGuide = attrs.getStringOrNull("ageRatingGuide")
		if (ageRating != null) {
			val ratingStr = if (ageGuide != null) "$ageRating - $ageGuide" else ageRating
			infobox.add("评分" to ratingStr)
		}

		// Episode count & length
		val episodeCount = attrs.optInt("episodeCount", 0)
		if (episodeCount > 0) {
			infobox.add("集数" to episodeCount.toString())
		}
		val episodeLength = attrs.optInt("episodeLength", 0)
		if (episodeLength > 0) {
			infobox.add("时长" to "${episodeLength}分钟/集")
		}

		// Average rating
		attrs.getStringOrNull("averageRating")?.let { infobox.add("平均评分" to "$it%") }

		// Rankings
		val popularityRank = attrs.optInt("popularityRank", 0)
		if (popularityRank > 0) infobox.add("受欢迎排名" to "#$popularityRank")
		val ratingRank = attrs.optInt("ratingRank", 0)
		if (ratingRank > 0) infobox.add("好评排名" to "#$ratingRank")

		// --- Tags (categories) ---
		val tags = mutableListOf<String>()
		if (included != null) {
			for (i in 0 until included.length()) {
				val item = included.optJSONObject(i) ?: continue
				if (item.optString("type") == "categories") {
					item.optJSONObject("attributes")?.getStringOrNull("title")?.let { tags.add(it) }
				}
			}
		}

		// --- Related works ---
		val relatedWorks = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		if (included != null) {
			// Build a map of included media by type+id for quick lookup
			val mediaMap = HashMap<String, JSONObject>()
			for (i in 0 until included.length()) {
				val item = included.optJSONObject(i) ?: continue
				val type = item.optString("type")
				if (type == "anime" || type == "manga") {
					val itemId = item.optString("id")
					mediaMap["$type:$itemId"] = item
				}
			}

			// Parse mediaRelationships to get role and destination
			for (i in 0 until included.length()) {
				val item = included.optJSONObject(i) ?: continue
				if (item.optString("type") != "mediaRelationships") continue
				val role = item.optJSONObject("attributes")?.getStringOrNull("role") ?: continue
				val destData = item.optJSONObject("relationships")
					?.optJSONObject("destination")
					?.optJSONObject("data") ?: continue
				val destType = destData.optString("type")
				val destId = destData.optString("id")
				val destMedia = mediaMap["$destType:$destId"] ?: continue
				val destAttrs = destMedia.optJSONObject("attributes") ?: continue
				val destTitle = destAttrs.optString("canonicalTitle", "")
				val destSlug = destAttrs.optString("slug", "")
				val destPoster = destAttrs.optJSONObject("posterImage")
				val destCover = destPoster?.getStringOrNull("small")
					?: destPoster?.getStringOrNull("medium").orEmpty()
				val destIdLong = destId.toLongOrNull() ?: continue

				val roleLabel = when (role) {
					"sequel" -> "续集"
					"prequel" -> "前传"
					"alternative_setting" -> "替代设定"
					"alternative_version" -> "替代版本"
					"side_story" -> "番外篇"
					"parent_story" -> "母篇"
					"summary" -> "总集篇"
					"full_story" -> "完整版"
					"spinoff" -> "衍生作"
					"adaptation" -> "改编"
					"character" -> "角色"
					"other" -> "其他"
					else -> role
				}

				relatedWorks.add(
					ScrobblerContentInfo.RelatedWork(
						id = destIdLong,
						title = destTitle,
						coverUrl = destCover,
						relationship = roleLabel,
						url = "$BASE_WEB_URL/$destType/$destSlug",
					),
				)
			}
		}

		// --- Episodes (separate request, only for anime or if applicable) ---
		val episodes = mutableListOf<ScrobblerContentInfo.EpisodeInfo>()
		try {
			val epRequest = Request.Builder().get()
				.url("$BASE_WEB_URL/api/edge/$mediaType/$id/episodes?page[limit]=40&sort=number")
			val epResponse = okHttp.newCall(epRequest.build()).await().parseJson()
			val epData = epResponse.optJSONArray("data")
			if (epData != null) {
				for (i in 0 until epData.length()) {
					val ep = epData.optJSONObject(i) ?: continue
					val epAttrs = ep.optJSONObject("attributes") ?: continue
					val epNum = epAttrs.optInt("number", i + 1)
					val epTitle = epAttrs.optString("canonicalTitle", "").ifBlank {
						"Episode $epNum"
					}
					val thumbnail = epAttrs.optJSONObject("thumbnail")
					val thumbUrl = thumbnail?.getStringOrNull("original")
						?: thumbnail?.getStringOrNull("large")
						?: thumbnail?.getStringOrNull("small")
					episodes.add(
						ScrobblerContentInfo.EpisodeInfo(
							number = epNum.toString(),
							title = epTitle,
							url = "$BASE_WEB_URL/$mediaType/$slug/episodes/$epNum",
							thumbnailUrl = thumbUrl,
						),
					)
				}
			}
		} catch (_: Exception) {
			// Episodes not available for some content types, that's OK
		}

		return ScrobblerContentInfo(
			id = mainData.getAsLong("id"),
			name = canonicalTitle.ifBlank { "Unknown" },
			cover = cover,
			url = "$BASE_WEB_URL/$mediaType/$slug",
			descriptionHtml = synopsis,
			tags = tags,
			infoboxProperties = infobox,
			episodes = episodes,
			commentThreads = discussion.commentThreads,
			reviews = discussion.reviews,
			relatedWorks = relatedWorks,
		)
	}

	private suspend fun fetchDiscussionPayload(
		mediaType: String,
		mediaId: Long,
	): KitsuDiscussionPayload {
		val filterKey = if (mediaType == "anime") "animeId" else "mangaId"
		val commentThreads = runCatching {
			val request = Request.Builder()
				.get()
				.url(
					"$BASE_WEB_URL/api/edge/media-reactions?page[limit]=10" +
						"&filter[$filterKey]=$mediaId&include=user",
				)
			val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
			parseMediaReactionThreads(response)
		}.getOrElse { emptyList() }
		val reviews = runCatching {
			val request = Request.Builder()
				.get()
				.url("$BASE_WEB_URL/api/edge/$mediaType/$mediaId/reviews?page[limit]=10&include=user")
			val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
			parseReviews(response)
		}.getOrElse { emptyList() }
		return KitsuDiscussionPayload(
			commentThreads = commentThreads,
			reviews = reviews,
		)
	}

	private suspend fun getPersonInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url(
				"$BASE_WEB_URL/api/edge/people/$id" +
					"?include=voices.mediaCharacter.media,voices.mediaCharacter.character,staff.media"
			)
		val json = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
		val data = json.getJSONObject("data")
		val attrs = data.getJSONObject("attributes")
		val included = json.optJSONArray("included")

		val mediaMap = included.buildIncludedMediaMap()
		val characterMap = included.buildIncludedCharacterMap()
		val mediaCharacterMap = included.buildIncludedMediaCharacterMap()

		val infobox = buildList {
			val description = attrs.getStringOrNull("description")?.toPlainText()
			if (!description.isNullOrBlank() && description != "Wikipedia" && description != "AniDB") {
				add("简介来源" to description)
			}
		}
		val authorAliases = attrs.optString("name").takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()

		val voicedWorks = buildList {
			if (included != null) {
				for (i in 0 until included.length()) {
					val item = included.optJSONObject(i) ?: continue
					if (item.optString("type") != "characterVoices") continue
					val voiceAttrs = item.optJSONObject("attributes")
					val locale = voiceAttrs?.getStringOrNull("locale")
					if (locale != null && locale != "ja_jp") continue
					val mediaCharacterId = item.optJSONObject("relationships")
						?.optJSONObject("mediaCharacter")
						?.optJSONObject("data")
						?.optString("id")
						?.takeIf { it.isNotBlank() }
						?: continue
					val mediaCharacter = mediaCharacterMap[mediaCharacterId] ?: continue
					val mediaId = mediaCharacter.relationshipId("media") ?: continue
					val media = mediaMap[mediaId] ?: continue
					val characterId = mediaCharacter.relationshipId("character")
					val character = characterId?.let(characterMap::get)
					add(
						media.toKitsuRelatedWork(
							relationship = character?.displayEntityName()?.let { "配音角色 · $it" } ?: "配音作品",
						) ?: continue,
					)
				}
			}
		}.distinctBy { it.id to it.relationship }

		val staffWorks = buildList {
			if (included != null) {
				for (i in 0 until included.length()) {
					val item = included.optJSONObject(i) ?: continue
					if (item.optString("type") != "mediaStaff") continue
					val role = item.optJSONObject("attributes")?.getStringOrNull("role")
					val mediaId = item.relationshipId("media") ?: continue
					val media = mediaMap[mediaId] ?: continue
					add(
						media.toKitsuRelatedWork(
							relationship = role?.takeIf { it.isNotBlank() } ?: "Staff",
						) ?: continue,
					)
				}
			}
		}.distinctBy { it.id to it.relationship }

		val extraSections = buildList {
			if (voicedWorks.isNotEmpty()) {
				add(
					ScrobblerContentInfo.RelatedSection(
						title = "配音作品",
						items = voicedWorks,
					),
				)
			}
			if (staffWorks.isNotEmpty()) {
				add(
					ScrobblerContentInfo.RelatedSection(
						title = "参与作品",
						items = staffWorks,
					),
				)
			}
		}

		return ScrobblerContentInfo(
			id = id,
			name = attrs.getStringOrNull("name").orEmpty().ifBlank { "Unknown" },
			cover = attrs.optJSONObject("image").bestKitsuImage().orEmpty(),
			url = "$BASE_WEB_URL/people/$id",
			descriptionHtml = attrs.getStringOrNull("description")
				?.takeIf { it.isNotBlank() && it != "Wikipedia" && it != "AniDB" }
				?.replace("\n", "<br>")
				.orEmpty(),
			authors = authorAliases,
			infoboxProperties = infobox,
			extraSections = extraSections,
			actions = listOf(
				ScrobblerContentInfo.ExternalAction(
					title = "人物主页",
					url = "$BASE_WEB_URL/people/$id",
				),
			),
		)
	}

	private suspend fun getCharacterInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url(
				"$BASE_WEB_URL/api/edge/characters/$id" +
					"?include=mediaCharacters.media,mediaCharacters.voices.person"
			)
		val json = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
		val data = json.getJSONObject("data")
		val attrs = data.getJSONObject("attributes")
		val included = json.optJSONArray("included")

		val mediaMap = included.buildIncludedMediaMap()
		val peopleMap = included.buildIncludedPeopleMap()
		val voiceMap = included.buildIncludedVoiceMap()

		val relatedWorks = buildList {
			if (included != null) {
				for (i in 0 until included.length()) {
					val item = included.optJSONObject(i) ?: continue
					if (item.optString("type") != "mediaCharacters") continue
					val role = item.optJSONObject("attributes")?.getStringOrNull("role")
					val mediaId = item.relationshipId("media") ?: continue
					val media = mediaMap[mediaId] ?: continue
					add(media.toKitsuRelatedWork(role?.toKitsuCharacterRoleLabel()) ?: continue)
				}
			}
		}.distinctBy { it.id }

		val voiceActors = buildList {
			if (included != null) {
				for (i in 0 until included.length()) {
					val item = included.optJSONObject(i) ?: continue
					if (item.optString("type") != "mediaCharacters") continue
					val voiceIds = item.optJSONObject("relationships")
						?.optJSONObject("voices")
						?.optJSONArray("data")
						?: continue
					for (j in 0 until voiceIds.length()) {
						val voiceRef = voiceIds.optJSONObject(j) ?: continue
						val voiceId = voiceRef.optString("id").takeIf { it.isNotBlank() } ?: continue
						val voice = voiceMap[voiceId] ?: continue
						val locale = voice.optJSONObject("attributes")?.getStringOrNull("locale")
						if (locale != null && locale != "ja_jp") continue
						val personId = voice.relationshipId("person") ?: continue
						val person = peopleMap[personId] ?: continue
						add(
								ScrobblerContentInfo.RelatedWork(
									id = person.optString("id").toLongOrNull() ?: continue,
									title = person.displayEntityName(),
									coverUrl = person.optJSONObject("attributes")?.optJSONObject("image")?.bestKitsuImage().orEmpty(),
									relationship = locale?.toKitsuLocaleLabel(),
									url = "$BASE_WEB_URL/people/$personId",
								),
						)
					}
				}
			}
		}.distinctBy { it.id }

		val infobox = buildList {
			attrs.optJSONObject("names")?.getStringOrNull("ja_jp")?.let {
				add("日文名" to it)
			}
			attrs.optJSONArray("otherNames")?.toStringList()
				?.takeIf { it.isNotEmpty() }
				?.joinToString(" / ")
				?.let { add("别名" to it) }
		}

		return ScrobblerContentInfo(
			id = id,
			name = attrs.getStringOrNull("canonicalName").orEmpty().ifBlank {
				attrs.getStringOrNull("name").orEmpty().ifBlank { "Unknown" }
			},
			cover = attrs.optJSONObject("image").bestKitsuImage().orEmpty(),
			url = "$BASE_WEB_URL/characters/${attrs.getStringOrNull("slug") ?: id}",
			descriptionHtml = attrs.getStringOrNull("description")
				?.replace("\n", "<br>")
				.orEmpty(),
			authors = voiceActors.map { it.title },
			infoboxProperties = infobox,
			relatedWorks = relatedWorks,
			extraSections = listOfNotNull(
				voiceActors.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "声优",
						items = it,
					)
				},
			),
			actions = listOf(
				ScrobblerContentInfo.ExternalAction(
					title = "角色主页",
					url = "$BASE_WEB_URL/characters/${attrs.getStringOrNull("slug") ?: id}",
				),
			),
		)
	}

	private fun parseMediaReactionThreads(json: JSONObject): List<ScrobblerContentInfo.CommentThread> {
		val users = json.buildUserLookup()
		val data = json.optJSONArray("data") ?: return emptyList()
		return buildList {
			for (i in 0 until data.length()) {
				val item = data.optJSONObject(i) ?: continue
				val attrs = item.optJSONObject("attributes") ?: continue
				val reaction = attrs.getStringOrNull("reaction")
					?.trim()
					?.takeIf { it.isNotBlank() }
					?: continue
				val userId = item.optJSONObject("relationships")
					?.optJSONObject("user")
					?.optJSONObject("data")
					?.optString("id")
					?.takeIf { it.isNotBlank() }
				val user = userId?.let(users::get)
				add(
					ScrobblerContentInfo.CommentThread(
						id = item.optString("id").ifBlank { i.toString() },
						userName = user?.name ?: "Kitsu User",
						userUrl = user?.profileUrl,
						avatarUrl = user?.avatarUrl,
						postedAt = attrs.getStringOrNull("createdAt")?.take(10),
						content = reaction,
					),
				)
			}
		}
	}

	private fun parseReviews(json: JSONObject): List<ScrobblerContentInfo.ReviewEntry> {
		val users = json.buildUserLookup()
		val data = json.optJSONArray("data") ?: return emptyList()
		return buildList {
			for (i in 0 until data.length()) {
				val item = data.optJSONObject(i) ?: continue
				val attrs = item.optJSONObject("attributes") ?: continue
				val excerpt = attrs.getStringOrNull("contentFormatted")
					?.toPlainText()
					?: attrs.getStringOrNull("content")?.normalizeWhitespace()
					?: continue
				val trimmedExcerpt = excerpt.takeIf { it.isNotBlank() } ?: continue
				val userId = item.optJSONObject("relationships")
					?.optJSONObject("user")
					?.optJSONObject("data")
					?.optString("id")
					?.takeIf { it.isNotBlank() }
				val user = userId?.let(users::get)
				val reviewId = item.optString("id").ifBlank { i.toString() }
				add(
					ScrobblerContentInfo.ReviewEntry(
						id = reviewId,
						title = trimmedExcerpt.toReviewTitle(),
						authorName = user?.name ?: "Kitsu User",
						authorUrl = user?.profileUrl,
						avatarUrl = user?.avatarUrl,
						postedAt = attrs.getStringOrNull("createdAt")?.take(10),
						excerpt = trimmedExcerpt,
						url = "$BASE_WEB_URL/reviews/$reviewId",
						repliesCount = null,
					),
				)
			}
		}
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		val isAnime = isAnimeContent(mangaId)
		val typeKey = if (isAnime) "anime" else "manga"
		findExistingRate(scrobblerContentId, isAnime)?.let {
			saveRate(it, mangaId, typeKey)
			return
		}
		val user = cachedUser ?: loadUser()
		val payload = JSONObject()
		payload.putJO("data") {
			put("type", "libraryEntries")
			putJO("attributes") {
				put("status", "planned") // will be updated by next call
				put("progress", 0)
			}
			putJO("relationships") {
				putJO(typeKey) {
					putJO("data") {
						put("type", typeKey)
						put("id", scrobblerContentId)
					}
				}
				putJO("user") {
					putJO("data") {
						put("type", "users")
						put("id", user.id)
					}
				}
			}
		}
		val request = Request.Builder()
			.url("$BASE_WEB_URL/api/edge/library-entries?include=$typeKey")
			.post(payload.toKitsuRequestBody())
		val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess().getJSONObject("data")
		saveRate(response, mangaId, typeKey)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val typeKey = if (isAnimeContent(mangaId)) "anime" else "manga"
		val payload = JSONObject()
		payload.putJO("data") {
			put("type", "libraryEntries")
			put("id", rateId)
			putJO("attributes") {
				put("progress", chapter)
			}
		}
		val request = Request.Builder()
			.url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=$typeKey")
			.patch(payload.toKitsuRequestBody())
		val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess().getJSONObject("data")
		saveRate(response, mangaId, typeKey)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val typeKey = if (isAnimeContent(mangaId)) "anime" else "manga"
		val payload = JSONObject()
		payload.putJO("data") {
			put("type", "libraryEntries")
			put("id", rateId)
			putJO("attributes") {
				put("status", status)
				put("ratingTwenty", (rating * 20).toInt().coerceIn(2, 20))
				put("notes", comment)
			}
		}
		val request = Request.Builder()
			.url("$BASE_WEB_URL/api/edge/library-entries/$rateId?include=$typeKey")
			.patch(payload.toKitsuRequestBody())
		val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess().getJSONObject("data")
		saveRate(response, mangaId, typeKey)
	}

	suspend fun syncLibraryFromRemote(): Int {
		val userId = (cachedUser ?: loadUser()).id
		val oldMappings = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.KITSU.id)
			.preferredMangaMappingByTargetId()

		val synced = ArrayList<ScrobblingEntity>()
		var offset = 0
		val limit = 50
		while (true) {
			val request = Request.Builder()
				.get()
				.url("$BASE_WEB_URL/api/edge/library-entries?page[limit]=$limit&page[offset]=$offset&filter[userId]=$userId&include=manga,anime")
			val response = okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
			val data = response.optJSONArray("data") ?: break
			if (data.length() == 0) {
				break
			}
			val mediaMap = buildSyncMediaMap(response.optJSONArray("included"))
			for (i in 0 until data.length()) {
				val json = data.optJSONObject(i) ?: continue
				val attrs = json.optJSONObject("attributes") ?: continue
				val rels = json.optJSONObject("relationships")
				val media = rels?.optJSONObject("manga")?.optJSONObject("data")
					?: rels?.optJSONObject("anime")?.optJSONObject("data")
				val targetId = media?.optString("id")?.toLongOrNull() ?: continue
				val mediaType = media?.optString("type") ?: ""
				val mappedContentId = oldMappings[targetId] ?: 0L

				val includedMedia = mediaMap["$mediaType-$targetId"]
				val mediaAttrs = includedMedia?.optJSONObject("attributes")
				val title = mediaAttrs?.optString("canonicalTitle", "")
					?: mediaAttrs?.optJSONObject("titles")?.let { t ->
						t.getStringOrNull("en") ?: t.getStringOrNull("en_jp") ?: t.getStringOrNull("ja_jp")
					}
				val posterImage = mediaAttrs?.optJSONObject("posterImage")
				val cover = posterImage?.getStringOrNull("small")
					?: posterImage?.getStringOrNull("medium").orEmpty()
				val slug = mediaAttrs?.optString("slug", "") ?: ""
				val remoteUrl = if (slug.isNotBlank()) "$BASE_WEB_URL/$mediaType/$slug" else ""

				synced.add(
					ScrobblingEntity(
						scrobbler = ScrobblerService.KITSU.id,
						id = json.optString("id").toIntOrNull() ?: continue,
						mangaId = mappedContentId,
						targetId = targetId,
						status = attrs.getStringOrNull("status"),
						chapter = attrs.getIntOrDefault("progress", 0),
						comment = attrs.getStringOrNull("notes"),
						rating = (attrs.getFloatOrDefault("ratingTwenty", 0f) / 20f).coerceIn(0f, 1f),
						remoteTitle = title?.takeIf { it.isNotBlank() },
						remoteCoverUrl = cover,
						remoteUrl = remoteUrl,
						mediaType = mediaType,
					),
				)
			}
			offset += data.length()
		}

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.KITSU.id)
			synced.forEach { entity ->
				db.upsertScrobbling(entity, workResolver)
			}
		}
		return synced.size
	}

	private fun buildSyncMediaMap(included: JSONArray?): Map<String, JSONObject> {
		if (included == null) return emptyMap()
		return LinkedHashMap<String, JSONObject>(included.length()).apply {
			for (i in 0 until included.length()) {
				val item = included.optJSONObject(i) ?: continue
				val type = item.optString("type")
				if (type != "anime" && type != "manga") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put("$type-$id", item)
			}
		}
	}

	private fun JSONObject.valuesToStringList(): List<String> {
		val result = ArrayList<String>(length())
		for (key in keys()) {
			result.add(getStringOrNull(key) ?: continue)
		}
		return result
	}

	private fun JSONArray.toStringList(): List<String> {
		val result = ArrayList<String>(length())
		for (i in 0 until length()) {
			optString(i).takeIf { it.isNotBlank() }?.let(result::add)
		}
		return result
	}

	private inline fun JSONObject.putJO(name: String, init: JSONObject.() -> Unit) {
		put(name, JSONObject().apply(init))
	}

	private fun JSONObject.toKitsuRequestBody() = toString().toRequestBody(VND_JSON.toMediaType())

	private fun JSONArray?.buildIncludedMediaMap(): Map<String, JSONObject> {
		val source = this ?: return emptyMap()
		return LinkedHashMap<String, JSONObject>(source.length()).apply {
			for (i in 0 until source.length()) {
				val item = source.optJSONObject(i) ?: continue
				val type = item.optString("type")
				if (type != "anime" && type != "manga") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put(id, item)
			}
		}
	}

	private fun JSONArray?.buildIncludedCharacterMap(): Map<String, JSONObject> {
		val source = this ?: return emptyMap()
		return LinkedHashMap<String, JSONObject>(source.length()).apply {
			for (i in 0 until source.length()) {
				val item = source.optJSONObject(i) ?: continue
				if (item.optString("type") != "characters") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put(id, item)
			}
		}
	}

	private fun JSONArray?.buildIncludedPeopleMap(): Map<String, JSONObject> {
		val source = this ?: return emptyMap()
		return LinkedHashMap<String, JSONObject>(source.length()).apply {
			for (i in 0 until source.length()) {
				val item = source.optJSONObject(i) ?: continue
				if (item.optString("type") != "people") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put(id, item)
			}
		}
	}

	private fun JSONArray?.buildIncludedMediaCharacterMap(): Map<String, JSONObject> {
		val source = this ?: return emptyMap()
		return LinkedHashMap<String, JSONObject>(source.length()).apply {
			for (i in 0 until source.length()) {
				val item = source.optJSONObject(i) ?: continue
				if (item.optString("type") != "mediaCharacters") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put(id, item)
			}
		}
	}

	private fun JSONArray?.buildIncludedVoiceMap(): Map<String, JSONObject> {
		val source = this ?: return emptyMap()
		return LinkedHashMap<String, JSONObject>(source.length()).apply {
			for (i in 0 until source.length()) {
				val item = source.optJSONObject(i) ?: continue
				if (item.optString("type") != "characterVoices") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				put(id, item)
			}
		}
	}

	private fun JSONObject.relationshipId(name: String): String? {
		return optJSONObject("relationships")
			?.optJSONObject(name)
			?.optJSONObject("data")
			?.optString("id")
			?.takeIf { it.isNotBlank() }
	}

	private fun JSONObject.bestKitsuImage(): String? {
		return getStringOrNull("large")
			?: getStringOrNull("medium")
			?: getStringOrNull("small")
			?: getStringOrNull("original")
			?: getStringOrNull("tiny")
	}

	private fun JSONObject.displayEntityName(): String {
		val attrs = optJSONObject("attributes")
		return attrs?.getStringOrNull("canonicalName")
			?: attrs?.getStringOrNull("name")
			?: attrs?.optJSONObject("names")?.getStringOrNull("en")
			?: attrs?.optJSONObject("names")?.getStringOrNull("ja_jp")
			?: attrs?.optJSONObject("titles")?.getStringOrNull("en_jp")
			?: attrs?.optJSONObject("titles")?.getStringOrNull("en")
			?: attrs?.optJSONObject("titles")?.getStringOrNull("ja_jp")
			?: attrs?.getStringOrNull("canonicalTitle")
			?: optString("id")
	}

	private fun JSONObject.toKitsuRelatedWork(relationship: String? = null): ScrobblerContentInfo.RelatedWork? {
		val id = optString("id").toLongOrNull() ?: return null
		val attrs = optJSONObject("attributes") ?: return null
		val type = optString("type").takeIf { it == "anime" || it == "manga" } ?: return null
		val slug = attrs.getStringOrNull("slug") ?: return null
		return ScrobblerContentInfo.RelatedWork(
			id = id,
			title = attrs.displayMediaTitle(),
			coverUrl = attrs.optJSONObject("posterImage").bestKitsuImage().orEmpty(),
			relationship = relationship,
			url = "$BASE_WEB_URL/$type/$slug",
		)
	}

	private fun JSONObject.displayMediaTitle(): String {
		return getStringOrNull("canonicalTitle")
			?: optJSONObject("titles")?.getStringOrNull("en_jp")
			?: optJSONObject("titles")?.getStringOrNull("en")
			?: optJSONObject("titles")?.getStringOrNull("ja_jp")
			?: getStringOrNull("name")
			?: "Unknown"
	}

	private fun String.toKitsuCharacterRoleLabel(): String {
		return when (this.lowercase()) {
			"main" -> "主角"
			"supporting" -> "配角"
			else -> this
		}
	}

	private fun String.toKitsuLocaleLabel(): String {
		return when (this.lowercase()) {
			"ja_jp" -> "日配"
			"en" -> "英配"
			"pt_br" -> "葡配"
			"es" -> "西配"
			"fr" -> "法配"
			"de" -> "德配"
			"ko" -> "韩配"
			"it" -> "意配"
			else -> this
		}
	}

	private suspend fun findExistingRate(scrobblerContentId: Long, isAnime: Boolean): JSONObject? {
		val userId = (cachedUser ?: loadUser()).id
		val filterKey = if (isAnime) "anime_id" else "manga_id"
		val includeKey = if (isAnime) "anime" else "manga"
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/library-entries?filter[$filterKey]=$scrobblerContentId&filter[userId]=$userId&include=$includeKey")
		val data = okHttp.newCall(request.build()).await().parseJsonOrNull()?.optJSONArray("data") ?: return null
		return data.optJSONObject(0)
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long, typeKey: String) {
		val attrs = json.getJSONObject("attributes")
		val existingEntity = db.findScrobblingByWorkOrManga(ScrobblerService.KITSU.id, mangaId, workResolver)
		val mediaId = existingEntity?.targetId ?: json.optJSONObject("relationships")
			?.optJSONObject(typeKey)
			?.optJSONObject("data")
			?.let { media ->
				if (media.isNull("id")) null else media.getAsLong("id")
			}
			?: throw IllegalArgumentException("Kitsu $typeKey relationship missing for manga $mangaId")
		val preview = existingEntity?.takeIf {
			!it.remoteTitle.isNullOrBlank() || !it.remoteCoverUrl.isNullOrBlank() || !it.remoteUrl.isNullOrBlank()
		} ?: findPreviewInfo(mediaId = mediaId, typeKey = typeKey)
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.KITSU.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = mediaId,
			status = attrs.getString("status"),
			chapter = attrs.getIntOrDefault("progress", 0),
			comment = attrs.getStringOrNull("notes"),
			rating = (attrs.getFloatOrDefault("ratingTwenty", 0f) / 20f).coerceIn(0f, 1f),
			mediaType = typeKey,
			remoteTitle = preview?.remoteTitle,
			remoteCoverUrl = preview?.remoteCoverUrl,
			remoteUrl = preview?.remoteUrl,
		)
		db.upsertScrobblingForManga(entity, workResolver, mangaId = mangaId)
	}

	private suspend fun findPreviewInfo(mediaId: Long, typeKey: String): ScrobblingEntity? {
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/$typeKey/$mediaId")
		val response = runCatching {
			okHttp.newCall(request.build()).await().parseJson().ensureSuccess()
		}.getOrNull() ?: return null
		val data = response.optJSONObject("data") ?: return null
		val attrs = data.optJSONObject("attributes") ?: return null
		val title = attrs.optString("canonicalTitle", "").ifBlank {
			attrs.optJSONObject("titles")?.let { titles ->
				titles.getStringOrNull("en") ?: titles.getStringOrNull("en_jp") ?: titles.getStringOrNull("ja_jp")
			}.orEmpty()
		}.takeIf { it.isNotBlank() }
		val posterImage = attrs.optJSONObject("posterImage")
		val cover = posterImage?.getStringOrNull("small")
			?: posterImage?.getStringOrNull("medium")
			?: posterImage?.getStringOrNull("large")
		val slug = attrs.optString("slug", "")
		val remoteUrl = slug.takeIf { it.isNotBlank() }?.let { "$BASE_WEB_URL/$typeKey/$it" }
		return ScrobblingEntity(
			scrobbler = ScrobblerService.KITSU.id,
			id = 0,
			mangaId = 0L,
			targetId = mediaId,
			status = null,
			chapter = 0,
			comment = null,
			rating = 0f,
			mediaType = typeKey,
			remoteTitle = title,
			remoteCoverUrl = cover,
			remoteUrl = remoteUrl,
		)
	}

	suspend fun persistPreview(
		mangaId: Long,
		targetId: Long,
		mediaType: String,
		title: String?,
		coverUrl: String?,
		url: String?,
	): Boolean {
		if (targetId <= 0L) return false
		val entities = db.getScrobblingDao().findAllByTargetId(ScrobblerService.KITSU.id, targetId)
			.filter {
				mediaType.isBlank() || it.mediaType == mediaType || it.mangaId == mangaId
			}
		val preferred = entities.preferredScrobblingEntity() ?: return false
		val normalizedTitle = title?.takeIf { it.isNotBlank() }
		val normalizedCover = coverUrl?.takeIf { it.isNotBlank() }
		val normalizedUrl = url?.takeIf { it.isNotBlank() }
		var updated = false
		entities.forEach { entity ->
			if (
				entity.remoteTitle == normalizedTitle &&
				entity.remoteCoverUrl == normalizedCover &&
				entity.remoteUrl == normalizedUrl
			) {
				return@forEach
			}
			db.upsertScrobblingPreview(
				entity = entity,
				workResolver = workResolver,
				title = normalizedTitle ?: entity.remoteTitle,
				coverUrl = normalizedCover ?: entity.remoteCoverUrl,
				url = normalizedUrl ?: entity.remoteUrl,
			)
			updated = true
		}
		if (updated) {
			android.util.Log.d("KitsuRepo", "persistPreview: targetId=$targetId ownerMangaId=${preferred.mangaId}")
		}
		return updated
	}

	private fun JSONObject.ensureSuccess(): JSONObject {
		val error = optJSONArray("errors")?.optJSONObject(0) ?: return this
		val title = error.getString("title")
		val detail = error.getStringOrNull("detail")
		throw IOException("$title: $detail")
	}

	private fun JSONObject.getAsLong(name: String): Long = when (val rawValue = opt(name)) {
		is Long -> rawValue
		is Number -> rawValue.toLong()
		is String -> rawValue.toLong()
		else -> throw IllegalArgumentException("Value $rawValue at \"$name\" is not of type long")
	}

	private suspend fun loadStats(userId: Long): ScrobblerUserStats? {
		val request = Request.Builder()
			.get()
			.url("$BASE_WEB_URL/api/edge/users/$userId/stats")
		val data = okHttp.newCall(request.build()).await().parseJson().ensureSuccess().optJSONArray("data")
			?: return null
		var animeCount: Int? = null
		var mangaCount: Int? = null
		var episodesWatched: Int? = null
		var chaptersRead: Int? = null
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			val attrs = item.optJSONObject("attributes") ?: continue
			val statsData = attrs.optJSONObject("statsData") ?: continue
			when (attrs.getStringOrNull("kind")) {
				"anime-amount-consumed" -> {
					animeCount = statsData.optInt("completed").takeIf { it > 0 }
						?: statsData.optInt("media").takeIf { it > 0 }
					episodesWatched = statsData.optInt("units").takeIf { it > 0 }
				}
				"manga-amount-consumed" -> {
					mangaCount = statsData.optInt("completed").takeIf { it > 0 }
						?: statsData.optInt("media").takeIf { it > 0 }
					chaptersRead = statsData.optInt("units").takeIf { it > 0 }
				}
			}
		}
		if (animeCount == null && mangaCount == null && episodesWatched == null && chaptersRead == null) {
			return null
		}
		return ScrobblerUserStats(
			animeCount = animeCount,
			mangaCount = mangaCount,
			episodesWatched = episodesWatched,
			chaptersRead = chaptersRead,
		)
	}

	private fun JSONObject.buildUserLookup(): Map<String, KitsuUserProfile> {
		val included = optJSONArray("included") ?: return emptyMap()
		return buildMap {
			for (i in 0 until included.length()) {
				val item = included.optJSONObject(i) ?: continue
				if (item.optString("type") != "users") continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				val attrs = item.optJSONObject("attributes")
				val slug = attrs?.getStringOrNull("slug")
				put(
					id,
					KitsuUserProfile(
						name = attrs?.getStringOrNull("name")?.takeIf { it.isNotBlank() } ?: "Kitsu User",
						profileUrl = slug?.let { "$BASE_WEB_URL/users/$it" },
						avatarUrl = attrs?.optJSONObject("avatar")?.getStringOrNull("small")
							?: attrs?.optJSONObject("avatar")?.getStringOrNull("medium")
							?: attrs?.optJSONObject("avatar")?.getStringOrNull("original"),
					),
				)
			}
		}
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

	private data class KitsuDiscussionPayload(
		val commentThreads: List<ScrobblerContentInfo.CommentThread> = emptyList(),
		val reviews: List<ScrobblerContentInfo.ReviewEntry> = emptyList(),
	)

	private data class KitsuUserProfile(
		val name: String,
		val profileUrl: String?,
		val avatarUrl: String?,
	)
}
