package org.skepsun.kototoro.scrobbling.bangumi.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.ensureSuccess
import org.skepsun.kototoro.core.util.ext.parseJsonOrNull
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.parseJson
import org.jsoup.Jsoup
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
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

private const val REDIRECT_URI = "kototoro://bangumi-auth"
private const val OFFICIAL_WEB_URL = "https://bangumi.tv/"
private const val OFFICIAL_API_URL = "https://api.bgm.tv/"
private const val BANGUMI_LOL_WEB_URL = "https://bangumi.lol/"
private const val BANGUMI_LOL_API_URL = "https://api.bangumi.lol/"

@Singleton
class BangumiRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.BANGUMI) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.BANGUMI) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val workResolver: WorkResolver,
) : ScrobblerRepository, ScrobblerUserProfileRepository {

	private val clientId = context.getString(R.string.bangumi_clientId)
	private val clientSecret = context.getString(R.string.bangumi_clientSecret)
	private val browserFiltersCache = ConcurrentHashMap<String, BangumiBrowserFilters>()

	private val publicEndpoints: BangumiEndpointUrls
		get() = when (settings.bangumiMirror) {
			AppSettings.BangumiMirror.BANGUMI_LOL -> BangumiEndpointUrls(
				webBaseUrl = BANGUMI_LOL_WEB_URL,
				apiBaseUrl = BANGUMI_LOL_API_URL,
			)
			AppSettings.BangumiMirror.NATIVE -> BangumiEndpointUrls(
				webBaseUrl = OFFICIAL_WEB_URL,
				apiBaseUrl = OFFICIAL_API_URL,
			)
			AppSettings.BangumiMirror.CUSTOM -> {
				val webBaseUrl = normalizeBangumiBaseUrl(settings.bangumiMirrorCustomBase, BANGUMI_LOL_WEB_URL)
				BangumiEndpointUrls(
					webBaseUrl = webBaseUrl,
					apiBaseUrl = inferBangumiApiBaseUrl(webBaseUrl),
				)
			}
		}

	override val oauthUrl: String
		get() = "${OFFICIAL_WEB_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=${REDIRECT_URI}&response_type=code"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		body.add("client_id", clientId)
		body.add("client_secret", clientSecret)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code", code)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken))
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${OFFICIAL_WEB_URL}oauth/access_token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		return loadUserProfile().user
	}

	override suspend fun loadUserProfile(): ScrobblerUserProfile {
		val request = Request.Builder()
			.url(officialApiUrl("v0/me"))
			.get()
		val jo = okHttp.newCall(request.build()).await().parseJson()
		val user = ScrobblerUser(
			id = jo.getLong("id"),
			nickname = jo.getString("nickname"),
			avatar = jo.getJSONObject("avatar").getStringOrNull("medium"),
			service = ScrobblerService.BANGUMI,
		).also { storage.user = it }
		val username = jo.getStringOrNull("username")
		val stats = username?.let {
			runCatching {
				loadCollectionStats(it)
			}.getOrNull()
		}
		return ScrobblerUserProfile(
			user = user,
			stats = stats,
		)
	}

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun unregister(mangaId: Long) {
		db.deleteScrobblingByWorkOrManga(ScrobblerService.BANGUMI.id, mangaId, workResolver)
	}

	override fun logout() {
		storage.clear()
	}

	private suspend fun loadCollectionStats(username: String): ScrobblerUserStats? {
		val animeCount = loadCollectionTotal(username, subjectType = 2)
		val mangaCount = loadCollectionTotal(username, subjectType = 1)
		if (animeCount == null && mangaCount == null) {
			return null
		}
		return ScrobblerUserStats(
			animeCount = animeCount,
			mangaCount = mangaCount,
		)
	}

	private suspend fun loadCollectionTotal(username: String, subjectType: Int): Int? {
		val request = Request.Builder()
			.url(officialApiUrl("v0/users/$username/collections?subject_type=$subjectType&limit=1"))
			.get()
		val response = okHttp.newCall(request.build()).await().parseJsonOrNull() ?: return null
		return response.optInt("total").takeIf { it >= 0 }
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val requestBody = JSONObject().apply {
			put("keyword", query)
			put("filter", JSONObject().apply {
				put("type", JSONArray().apply { put(if (isAnime) 2 else 1) }) // 2 is Anime, 1 is Book
			})
		}.toString().toRequestBody("application/json".toMediaType())

		val request = Request.Builder()
			.url(bangumiApiUrl("v0/search/subjects?limit=10&offset=$offset"))
			.post(requestBody)

		val response = okHttp.newCall(request.build()).await().ensureSuccess().parseJson()
		val data = response.getJSONArray("data")
		return data.mapJSON { json ->
			ScrobblerContent(
				id = json.getLong("id"),
				name = json.getString("name_cn").ifBlank { json.getString("name") },
				altName = json.getString("name"),
				cover = json.getJSONObject("images").getString("medium"),
				url = bangumiWebUrl("subject/${json.getLong("id")}"),
				mediaType = json.optString("platform").takeIf { it.isNotBlank() },
					totalEpisodes = json.optInt("eps", -1).takeIf { it > 0 },
			)
		}
	}

	suspend fun getRankings(
		category: String, 
		page: Int,
		sortOrder: SortOrder? = null,
		listFilter: ContentListFilter? = null
	): List<ScrobblerContent> {
		val typePath = getBrowserPath(category)
		val tagPath = buildBrowserTagPath(listFilter)
		val sortStr = sortOrder.toBangumiSortKey()
		val url = buildString {
			append(bangumiWebUrl())
			append(typePath)
			if (tagPath.isNotBlank()) {
				append("/")
				append(tagPath)
			}
			append("?sort=")
			append(sortStr)
			append("&page=")
			append(page)
		}

		val request = Request.Builder()
			.url(url)
			.get()
			.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
			.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			.build()

		val doc = Jsoup.parse(okHttp.newCall(request).await().body?.string().orEmpty())
		return doc.select("ul#browserItemList > li").map { el ->
			val id = el.attr("id").substringAfter("item_").toLongOrNull() ?: 0L
			val titleWrapper = el.selectFirst("a.l")
			val name = titleWrapper?.text().orEmpty()
			val altName = el.selectFirst("small.grey")?.text() ?: name
			val coverUrl = el.selectFirst("a.subjectCover img.cover")?.attr("src") ?: ""
			val platformBadge = el.selectFirst("span.ico_subject_type")?.attr("title")?.takeIf { it.isNotBlank() }
			var cleanCover = coverUrl.replace("/s/", "/l/").replace("/m/", "/l/") // Get large cover instead of small
			if (cleanCover.startsWith("//")) {
				cleanCover = "https:$cleanCover"
			}
			
			ScrobblerContent(
				id = id,
				name = name,
				altName = altName,
				cover = if (cleanCover.startsWith("//")) "https:$cleanCover" else cleanCover,
				url = bangumiWebUrl("subject/$id"),
				mediaType = platformBadge,
				isBestMatch = false
			)
		}
	}

	suspend fun getBrowserFilterOptions(category: String, source: ContentSource): ContentListFilterOptions {
		if (category.startsWith("calendar")) {
			return ContentListFilterOptions()
		}
		val filters = browserFiltersCache.getOrPut(category) {
			loadBrowserFilters(category)
		}
		return ContentListFilterOptions(
			tagGroups = filters.groups.mapIndexed { index, group ->
				ContentTagGroup(
					title = group.title,
					tags = group.options.mapTo(LinkedHashSet()) { option ->
						ContentTag(
							title = option.title,
							key = buildBrowserTagKey(index, option.segment),
							source = source,
						)
					},
					isExclusive = true,
				)
			},
		)
	}

	suspend fun getDailyCalendar(): Map<Int, List<ScrobblerContent>> {
		val request = Request.Builder()
			.url(bangumiWebUrl())
			.get()
			.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
			.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			.build()

		val doc = Jsoup.parse(okHttp.newCall(request).await().body?.string().orEmpty())
		val map = mutableMapOf<Int, List<ScrobblerContent>>()
		
		doc.select("#home_calendar .week").forEach { weekEl ->
			val dayClass = weekEl.classNames().firstOrNull { it in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") }
			val dayInt = when (dayClass) {
				"Mon" -> 1
				"Tue" -> 2
				"Wed" -> 3
				"Thu" -> 4
				"Fri" -> 5
				"Sat" -> 6
				"Sun" -> 7
				else -> 0
			}
			if (dayInt == 0) return@forEach

			val items = weekEl.select(".coverList .thumbTip").map { el ->
				val id = el.attr("href").substringAfter("subject/").toLongOrNull() ?: 0L
				val rawName = el.attr("title").orEmpty()
				val name = rawName.split("<br")[0].trim()
				val altName = if (rawName.contains("<small>")) {
					rawName.substringAfter("<small>").substringBefore("</small>").trim()
				} else {
					name
				}
				val coverUrl = el.selectFirst("img")?.attr("src").orEmpty()
				// Strip Bangumi's resize proxy prefix (e.g. /r/100x100/) to get full-size image
				val cleanCover = coverUrl
					.replace(Regex("/r/\\d+x\\d+/"), "/")
					.replace("/g/", "/l/").replace("/s/", "/l/").replace("/m/", "/l/").replace("/c/", "/l/")

				ScrobblerContent(
					id = id,
					name = name,
					altName = altName,
					cover = if (cleanCover.startsWith("//")) "https:$cleanCover" else cleanCover,
					url = bangumiWebUrl("subject/$id"),
					isBestMatch = false
				)
			}.filter { it.id > 0L }.distinctBy { it.id }
			
			map[dayInt] = items
		}
		return map
	}

private suspend fun loadBrowserFilters(category: String): BangumiBrowserFilters {
		val request = Request.Builder()
			.url(bangumiWebUrl(getBrowserPath(category)))
			.get()
			.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
			.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			.build()
		val doc = Jsoup.parse(okHttp.newCall(request).await().body?.string().orEmpty())
		val root = doc.selectFirst("#columnSubjectBrowserB .sideInner") ?: return BangumiBrowserFilters(emptyList())
		val groups = mutableListOf<BangumiBrowserFilterGroup>()
		var currentTitle: String? = null
		root.children().forEach { child ->
			when {
				child.tagName() == "h2" && child.hasClass("subtitle") -> {
					currentTitle = child.text().trim()
				}
				child.tagName() == "ul" && child.hasClass("grouped") -> {
					val title = currentTitle ?: return@forEach
					currentTitle = null
					parseBrowserFilterGroup(category, title, child)?.let(groups::add)
				}
			}
		}
		return BangumiBrowserFilters(groups)
	}

	private fun parseBrowserFilterGroup(category: String, title: String, list: org.jsoup.nodes.Element): BangumiBrowserFilterGroup? {
		if (title == "标签" || title.contains("拼音")) {
			return null
		}
		val rawOptions = list.select("a.l[href]")
			.mapNotNull { anchor ->
				val href = anchor.attr("href").trim()
				if (href.isBlank() || href.startsWith("javascript:")) {
					return@mapNotNull null
				}
				val optionTitle = anchor.text().trim()
				if (optionTitle.isBlank() || optionTitle == "全部") {
					return@mapNotNull null
				}
				val segment = href.substringAfter("/$category/browser/", "")
					.substringBefore("?")
					.trim('/')
					.takeIf { it.isNotBlank() }
					?.let(::decodeBrowserSegment)
					?: return@mapNotNull null
				BangumiBrowserOption(optionTitle, segment)
			}
			.distinctBy { it.segment }
		if (rawOptions.isEmpty()) {
			return null
		}
		if (title == "时间") {
			val expandedOptions = LinkedHashMap<String, BangumiBrowserOption>()
			rawOptions.forEach { option ->
				expandedOptions[option.segment] = option
				val year = option.segment.removePrefix("airtime/").toIntOrNull()
				if (year != null) {
					val year年 = year.toString() + "\u5E74"
					BANGUMI_SEASONS.forEach { (month, seasonLabel) ->
						val segment = "airtime/$year-$month"
						expandedOptions.putIfAbsent(
							segment,
							BangumiBrowserOption("$year年$seasonLabel", segment),
						)
					}
				}
			}
			return BangumiBrowserFilterGroup(title = title, options = expandedOptions.values.toList())
		}
		return BangumiBrowserFilterGroup(title = title, options = rawOptions)
	}

	private fun decodeBrowserSegment(value: String): String {
		return URLDecoder.decode(value, StandardCharsets.UTF_8)
	}

	private fun buildBrowserTagPath(filter: ContentListFilter?): String {
		if (filter == null) {
			return ""
		}
		return filter.tags
			.mapNotNull { tag -> parseBrowserTagKey(tag.key) }
			.groupBy { selection -> selection.groupIndex }
			.toSortedMap()
			.values
			.mapNotNull { selections -> selections.firstOrNull()?.segment }
			.joinToString("/")
	}

	private fun buildBrowserTagKey(groupIndex: Int, segment: String): String {
		return "bgm|$groupIndex|$segment"
	}

	private fun parseBrowserTagKey(key: String): BrowserTagSelection? {
		val firstSeparator = key.indexOf('|')
		val secondSeparator = key.indexOf('|', firstSeparator + 1)
		if (firstSeparator < 0 || secondSeparator < 0 || !key.startsWith("bgm|")) {
			return null
		}
		val groupIndex = key.substring(firstSeparator + 1, secondSeparator).toIntOrNull() ?: return null
		val segment = key.substring(secondSeparator + 1).takeIf { it.isNotBlank() } ?: return null
		return BrowserTagSelection(groupIndex, segment)
	}

	private fun getBrowserPath(category: String): String = when (category) {
		"anime" -> "anime/browser"
		"book" -> "book/browser"
		"music" -> "music/browser"
		"game" -> "game/browser"
		"real" -> "real/browser"
		else -> "anime/browser"
	}

	private fun SortOrder?.toBangumiSortKey(): String = when (this) {
		SortOrder.POPULARITY -> "trends"
		SortOrder.ADDED -> "collects"
		SortOrder.NEWEST,
		SortOrder.UPDATED -> "date"
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC -> "title"
		SortOrder.RATING,
		null -> "rank"
		else -> "rank"
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		db.upsertScrobbling(
			ScrobblingEntity(
				scrobbler = ScrobblerService.BANGUMI.id,
				id = scrobblerContentId.toInt(),
				mangaId = mangaId,
				targetId = scrobblerContentId,
				status = "do",
				chapter = 0,
				comment = "",
				rating = 0f,
			),
			workResolver,
		)
		findExistingCollection(scrobblerContentId)?.let {
			saveCollection(it, mangaId)
			return
		}
		updateCollection(scrobblerContentId, 3, null, null, null, isCreate = true)
		findExistingCollection(scrobblerContentId)?.let {
			saveCollection(it, mangaId)
			return
		}
		db.deleteScrobblingByWorkOrManga(ScrobblerService.BANGUMI.id, mangaId, workResolver)
		throw IOException("Bangumi collection for subject $scrobblerContentId was not created remotely")
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.BANGUMI.id, mangaId, workResolver) ?: return
		updateCollection(entity.targetId, null, null, null, chapter)
		db.upsertScrobblingForManga(entity.copy(chapter = chapter), workResolver, mangaId = mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val entity = db.findScrobblingByWorkOrManga(ScrobblerService.BANGUMI.id, mangaId, workResolver) ?: return
		val bgmStatus = when (status) {
			"wish" -> 1
			"collect" -> 2
			"do" -> 3
			"on_hold" -> 4
			"dropped" -> 5
			else -> null
		}
		val score = if (rating > 0) (rating * 10).roundToInt() else null
		updateCollection(entity.targetId, bgmStatus, score, comment, null)
		db.upsertScrobblingForManga(
			entity.copy(
				status = status ?: entity.status,
				rating = rating,
				comment = comment ?: entity.comment,
			),
			workResolver,
			mangaId = mangaId,
		)
	}

	private suspend fun updateCollection(subjectId: Long, status: Int?, rate: Int?, comment: String?, ep: Int?, isCreate: Boolean = false) {
		val body = JSONObject()
		status?.let { body.put("type", it) }
		rate?.let { body.put("rate", it) }
		comment?.let { body.put("comment", it) }
		ep?.let { body.put("ep_status", it) }
		if (isCreate) {
			body.put("private", false)
		}

		val reqBody = body.toString().toByteArray(Charsets.UTF_8)
			.toRequestBody("application/json".toMediaType())
		val request = Request.Builder()
			.url(officialApiUrl("v0/users/-/collections/$subjectId"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json")
			.post(reqBody)

		okHttp.newCall(request.build()).await().use { response ->
			response.ensureSuccess()
		}
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val apiPayload = getSubjectDetailsFromApi(id)
		val htmlPayload = runCatching { getSubjectDetailsFromHtml(id) }.getOrNull()
		val mergedInfobox = mergeBangumiInfoboxProperties(
			apiPayload.infoboxProperties,
			htmlPayload?.infoboxProperties.orEmpty(),
		)
		return ScrobblerContentInfo(
			id = id,
			name = apiPayload.name.ifBlank { htmlPayload?.name ?: "Unknown" },
			cover = apiPayload.cover.ifBlank { htmlPayload?.cover.orEmpty() },
			url = bangumiWebUrl("subject/$id"),
			descriptionHtml = apiPayload.summary.ifBlank { htmlPayload?.summary.orEmpty() },
			contentType = apiPayload.contentType ?: resolveBangumiContentType(
				subjectType = apiPayload.subjectType,
				platform = apiPayload.platform,
				infoboxProperties = mergedInfobox,
			),
			score = apiPayload.score,
			rank = apiPayload.rank,
			tags = if (apiPayload.tags.isNotEmpty()) apiPayload.tags else htmlPayload?.tags.orEmpty(),
			authors = htmlPayload?.authors.orEmpty(),
			staff = htmlPayload?.staff.orEmpty(),
			infoboxProperties = mergedInfobox,
			episodes = htmlPayload?.episodes.orEmpty(),
			characters = htmlPayload?.characters.orEmpty(),
			commentThreads = htmlPayload?.commentThreads.orEmpty(),
			reviews = htmlPayload?.reviews.orEmpty(),
			relatedWorks = htmlPayload?.relatedWorks.orEmpty(),
			recommendations = htmlPayload?.recommendations.orEmpty(),
			extraSections = htmlPayload?.extraSections.orEmpty(),
			actions = htmlPayload?.actions.orEmpty(),
		)
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
		val endpoint = when (entityType) {
			EntityType.PERSON -> "persons"
			EntityType.CHARACTER -> "characters"
			else -> return emptyList()
		}
		val body = JSONObject()
			.put("keyword", query)
			.put("limit", limit)
			.put("offset", page.coerceAtLeast(0) * limit)
			.toString()
			.toRequestBody("application/json".toMediaType())
		val request = Request.Builder()
			.url(bangumiApiUrl("v0/search/$endpoint"))
			.post(body)
			.build()
		val data = okHttp.newCall(request).await().ensureSuccess().parseJson()
			.optJSONArray("data")
			?: return emptyList()
		return data.mapJSON { json ->
			val name = json.getStringOrNull("name") ?: "Unknown"
			ScrobblerContent(
				id = json.getLong("id"),
				name = name,
				altName = json.optJSONArray("infobox").toBangumiInfoboxProperties()
					.flatMap { (_, value) -> splitBangumiNames(value) }
					.firstOrNull { !it.equals(name, ignoreCase = true) },
				cover = json.optJSONObject("images")?.getStringOrNull("large")
					?: json.getStringOrNull("img")
					?: json.optJSONObject("images")?.getStringOrNull("medium"),
				url = bangumiWebUrl("person/${json.getLong("id")}").takeIf { entityType == EntityType.PERSON }
					?: bangumiWebUrl("character/${json.getLong("id")}"),
			)
		}
	}

	private suspend fun getSubjectDetailsFromApi(id: Long): BangumiApiSubjectPayload {
		val request = Request.Builder()
			.url(bangumiApiUrl("v0/subjects/$id"))
			.get()
		val json = okHttp.newCall(request.build()).await().ensureSuccess().parseJson()
		val platformType = json.optString("platform").takeIf { it.isNotBlank() }
		val subjectType = json.optInt("type").takeIf { it > 0 }
		val infoboxProperties = json.optJSONArray("infobox").toBangumiInfoboxProperties().let { list ->
			if (platformType != null && list.none { it.first == "类型" || it.first == "Platform" }) {
				listOf("类型" to platformType) + list
			} else {
				list
			}
		}
		return BangumiApiSubjectPayload(
			name = json.getStringOrNull("name_cn").orEmpty().ifBlank { json.getStringOrNull("name").orEmpty() },
			cover = json.optJSONObject("images")?.getStringOrNull("large")
				?: json.optJSONObject("images")?.getStringOrNull("common")
				?: json.optJSONObject("images")?.getStringOrNull("medium")
				?: "",
			summary = json.getStringOrNull("summary").orEmpty(),
			platform = platformType,
			subjectType = subjectType,
			contentType = resolveBangumiContentType(
				subjectType = subjectType,
				platform = platformType,
				infoboxProperties = infoboxProperties,
			),
			score = json.optJSONObject("rating")?.optDouble("score")?.takeIf { it > 0.0 }?.toFloat(),
			rank = json.optJSONObject("rating")?.optInt("rank")?.takeIf { it > 0 },
			tags = json.optJSONArray("tags").toBangumiTags(),
			infoboxProperties = infoboxProperties,
		)
	}

	private suspend fun getPersonInfo(id: Long): ScrobblerContentInfo {
		val doc = loadBangumiDocument(bangumiWebUrl("person/$id"))
		val voiceWorksDoc = runCatching {
			loadBangumiDocument(bangumiWebUrl("person/$id/works/voice"))
		}.getOrNull()
		val worksDoc = runCatching {
			loadBangumiDocument(bangumiWebUrl("person/$id/works"))
		}.getOrNull()

		val title = doc.selectFirst("#headerSubject .nameSingle a, #headerSubject h1 a, h1.nameSingle a")
			?.text()
			?.trim()
			.orEmpty()
		val altTitle = doc.selectFirst("#headerSubject .nameSingle small, h1.nameSingle small")
			?.text()
			?.trim()
			.takeIf { !it.isNullOrBlank() }
		val avatarUrl = doc.selectFirst("a.cover img.cover, .infobox_container img.cover, #columnA img.cover")
			?.attr("src")
			?.normalizeBangumiImageUrl()
			.orEmpty()
		val summary = doc.selectFirst("#subject_summary, #subject_summary .inner")?.html().orEmpty()
		val infoboxProperties = parseBangumiInfoboxProperties(doc)
		val aliases = infoboxProperties
			.filter { (key, _) ->
				key.contains("别名") || key.contains("中文名") || key.contains("日文名") || key.contains("英文名")
			}
			.flatMap { (_, value) -> splitBangumiNames(value) }
			.distinct()
		val voiceWorks = parseBangumiPersonVoiceWorks(voiceWorksDoc ?: doc)
		val generalWorks = parseBangumiSectionWorks(worksDoc ?: doc)
		val extraSections = buildList {
			if (voiceWorks.isNotEmpty()) {
				add(
					ScrobblerContentInfo.RelatedSection(
						title = "配音作品",
						items = voiceWorks.map { it.work },
					),
				)
			}
			if (generalWorks.isNotEmpty()) {
				add(
					ScrobblerContentInfo.RelatedSection(
						title = "参与作品",
						items = generalWorks,
					),
				)
			}
		}
		val authoredWorks = voiceWorks.mapNotNull { item ->
			val role = item.characterName?.takeIf { it.isNotBlank() }
			val work = item.work
			role?.let {
				"${work.title} ($it)"
			} ?: work.title
		}
		return ScrobblerContentInfo(
			id = id,
			name = title.ifBlank { altTitle ?: "Unknown" },
			cover = avatarUrl,
			url = bangumiWebUrl("person/$id"),
			descriptionHtml = summary,
			contentType = null,
			authors = (authoredWorks + aliases).distinct(),
			infoboxProperties = infoboxProperties,
			extraSections = extraSections,
			actions = listOfNotNull(
				ScrobblerContentInfo.ExternalAction(
					title = "角色作品",
					url = bangumiWebUrl("person/$id/works/voice"),
				),
				worksDoc?.let {
					ScrobblerContentInfo.ExternalAction(
						title = "参与作品",
						url = bangumiWebUrl("person/$id/works"),
					)
				},
			),
		)
	}

	private suspend fun getCharacterInfo(id: Long): ScrobblerContentInfo {
		val doc = loadBangumiDocument(bangumiWebUrl("character/$id"))
		val title = doc.selectFirst("#headerSubject .nameSingle a, #headerSubject h1 a, h1.nameSingle a")
			?.text()
			?.trim()
			.orEmpty()
		val altTitle = doc.selectFirst("#headerSubject .nameSingle small, h1.nameSingle small")
			?.text()
			?.trim()
			.takeIf { !it.isNullOrBlank() }
		val cover = doc.selectFirst("a.cover img.cover, .infobox_container img.cover, #columnA img.cover")
			?.attr("src")
			?.normalizeBangumiImageUrl()
			.orEmpty()
		val summary = doc.selectFirst("#subject_summary, #subject_summary .inner")?.html().orEmpty()
		val infoboxProperties = parseBangumiInfoboxProperties(doc)
		val relatedWorks = parseBangumiCharacterRelatedWorks(doc)
		val voiceActors = parseBangumiCharacterVoiceActors(doc)
		return ScrobblerContentInfo(
			id = id,
			name = title.ifBlank { altTitle ?: "Unknown" },
			cover = cover,
			url = bangumiWebUrl("character/$id"),
			descriptionHtml = summary,
			contentType = null,
			authors = voiceActors.map { it.name },
			infoboxProperties = infoboxProperties,
			relatedWorks = relatedWorks,
			extraSections = listOfNotNull(
				voiceActors.takeIf { it.isNotEmpty() }?.let { actors ->
					ScrobblerContentInfo.RelatedSection(
						title = "声优",
						items = actors.mapIndexed { index, actor ->
							ScrobblerContentInfo.RelatedWork(
								id = actor.id ?: -(index + 1).toLong(),
								title = actor.name,
								coverUrl = actor.avatarUrl.orEmpty(),
								url = actor.url.orEmpty(),
							)
						},
					)
				},
			),
			actions = listOf(
				ScrobblerContentInfo.ExternalAction(
					title = "角色主页",
					url = bangumiWebUrl("character/$id"),
				),
			),
		)
	}

	private suspend fun getSubjectDetailsFromHtml(id: Long): BangumiHtmlSubjectPayload {
		val doc = loadBangumiDocument(bangumiWebUrl("subject/$id"))

		val nameNative = doc.selectFirst("#headerSubject .nameSingle > a")?.text().orEmpty()
		val nameCn = doc.selectFirst("#headerSubject .nameSingle > a")?.attr("title").orEmpty()
		val finalName = if (nameCn.isNotBlank()) nameCn else nameNative
		
		val cover = doc.selectFirst("img.cover")?.attr("src")?.normalizeBangumiImageUrl().orEmpty()

		val summary = doc.selectFirst("#subject_summary")?.html().orEmpty()
		
		// Real user tags from .subject_tag_section
		val tagList = mutableListOf<String>()
		doc.select(".subject_tag_section .inner a span").forEach { span ->
			val tagName = span.text().trim()
			if (tagName.isNotBlank()) tagList.add(tagName)
		}
		
		// Infobox properties as key-value pairs
		val infoboxProperties = parseBangumiInfoboxProperties(doc)
		
		// Episodes from prg_list
		val episodes = mutableListOf<ScrobblerContentInfo.EpisodeInfo>()
		doc.select("ul.prg_list li a").forEach { a ->
			val epTitle = a.attr("title").trim()
			val epNumber = a.text().trim()
			val epUrl = a.attr("href")
			if (epTitle.isNotBlank()) {
				episodes.add(ScrobblerContentInfo.EpisodeInfo(
					number = epNumber,
					title = epTitle,
					url = if (epUrl.startsWith("/")) bangumiWebUrl(epUrl) else epUrl,
				))
			}
		}

		// Related works (关联条目)
		val relatedWorks = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		val relatedSection = doc.select(".subject_section").firstOrNull { section ->
			section.selectFirst("h2.subtitle")?.text()?.contains("关联条目") == true
		}
		relatedSection?.select("ul.browserCoverMedium li")?.forEach { li ->
			val relationship = li.selectFirst("span.sub")?.text()?.trim().orEmpty()
			val titleEl = li.selectFirst("a.title")
			val title = titleEl?.text().orEmpty()
			val href = titleEl?.attr("href").orEmpty()
			val relId = href.substringAfter("/subject/").toLongOrNull() ?: 0L
			val bgStyle = li.selectFirst("span.coverNeue")?.attr("style").orEmpty()
			val bgUrl = bgStyle.substringAfter("url('").substringBefore("')")
			val relCover = if (bgUrl.startsWith("//")) "https:$bgUrl" else bgUrl
			if (relId > 0 && title.isNotBlank()) {
				relatedWorks.add(ScrobblerContentInfo.RelatedWork(
					id = relId,
					title = title,
					coverUrl = relCover,
					relationship = relationship.ifBlank { null },
					url = bangumiWebUrl("subject/$relId"),
				))
			}
		}

		// Recommendations (喜欢...的会员大概会喜欢)
		val recommendations = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		val recSection = doc.select(".subject_section").firstOrNull { section ->
			section.selectFirst("h2.subtitle")?.text()?.contains("会员大概会喜欢") == true
		}
		recSection?.select("ul.coversSmall li")?.forEach { li ->
			val recLink = li.selectFirst("a.avatar")
			val recTitle = recLink?.attr("title")?.trim().orEmpty()
			val recHref = recLink?.attr("href").orEmpty()
			val recId = recHref.substringAfter("/subject/").toLongOrNull() ?: 0L
			val recBgStyle = li.selectFirst("span.coverNeue")?.attr("style").orEmpty()
			val recBgUrl = recBgStyle.substringAfter("url('").substringBefore("')")
			val recCover = if (recBgUrl.startsWith("//")) "https:$recBgUrl" else recBgUrl
			// Fallback title from p.info a
			val displayTitle = recTitle.ifBlank { li.selectFirst("p.info a")?.text().orEmpty() }
			if (recId > 0 && displayTitle.isNotBlank()) {
				recommendations.add(ScrobblerContentInfo.RelatedWork(
					id = recId,
					title = displayTitle,
					coverUrl = recCover,
					url = bangumiWebUrl("subject/$recId"),
				))
			}
		}

		val extraSections = doc.select(".subject_section").mapNotNull { section ->
			val title = section.selectFirst("h2.subtitle")?.text()?.trim().orEmpty()
			if (title.isBlank() || title.contains("关联条目") || title.contains("会员大概会喜欢")) {
				return@mapNotNull null
			}
			val items = parseBangumiSectionWorks(section)
			items.takeIf { it.isNotEmpty() }?.let {
				ScrobblerContentInfo.RelatedSection(
					title = title,
					items = it,
				)
			}
		}

		// Characters/voice actors
		val characters = parseBangumiCharacters(doc)
		val staff = parseBangumiStaff(doc)
		val authorsList = characters.map { character ->
			val voiceActors = character.voiceActors.joinToString(" / ") { it.name }
			if (voiceActors.isBlank()) {
				character.name
			} else {
				"${character.name} (CV: $voiceActors)"
			}
		}
		val commentThreads = parseBangumiCommentThreads(doc)
		val reviews = parseBangumiReviews(doc)

		return BangumiHtmlSubjectPayload(
			name = finalName.ifBlank { "Unknown" },
			cover = cover,
			summary = summary,
			tags = tagList,
			authors = authorsList,
			staff = staff,
			infoboxProperties = infoboxProperties,
			episodes = episodes,
			characters = characters,
			commentThreads = commentThreads,
			reviews = reviews,
			relatedWorks = relatedWorks,
			recommendations = recommendations,
			extraSections = extraSections,
			actions = listOf(
				ScrobblerContentInfo.ExternalAction(
					title = "长评",
					url = bangumiWebUrl("subject/$id/reviews"),
				),
				ScrobblerContentInfo.ExternalAction(
					title = "更多吐槽",
					url = bangumiWebUrl("subject/$id/comments"),
				),
			),
		)
	}

	private fun parseBangumiCharacters(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.CharacterInfo> {
		return doc.select(".subject_section #browserItemList > li.item").mapNotNull { item ->
			val titleLink = item.selectFirst("a.title[href*=/character/]")
				?: item.selectFirst("a.thumbTip[href*=/character/]")
				?: return@mapNotNull null
			val url = titleLink.absUrl("href").ifBlank {
				titleLink.attr("href").takeIf { it.isNotBlank() }?.let { href ->
					if (href.startsWith("/")) bangumiWebUrl(href) else href
				}.orEmpty()
			}
			val id = url.substringAfter("/character/").substringBefore('/').toLongOrNull() ?: return@mapNotNull null
			val name = titleLink.text().trim().ifBlank { titleLink.attr("title").trim() }
			if (name.isBlank()) {
				return@mapNotNull null
			}
			val style = item.selectFirst(".avatarCoverPortrait")?.attr("style").orEmpty()
			val coverUrl = extractBangumiCssUrl(style)
			val role = item.selectFirst(".badge_job_tip")?.text()?.trim().takeIf { !it.isNullOrBlank() }
			val voiceActors = item.select(".badge_actor a[href*=/person/]").mapNotNull { actorLink ->
				val actorUrl = actorLink.absUrl("href").ifBlank {
					actorLink.attr("href").takeIf { it.isNotBlank() }?.let { href ->
						if (href.startsWith("/")) bangumiWebUrl(href) else href
					}.orEmpty()
				}
				val actorId = actorUrl.substringAfter("/person/").substringBefore('/').toLongOrNull()
				val actorName = actorLink.text().trim()
				if (actorName.isBlank()) {
					return@mapNotNull null
				}
				ScrobblerContentInfo.PersonInfo(
					id = actorId,
					name = actorName,
					url = actorUrl.ifBlank { null },
				)
			}
			ScrobblerContentInfo.CharacterInfo(
				id = id,
				name = name,
				coverUrl = coverUrl,
				role = role,
				url = url,
				voiceActors = voiceActors,
			)
		}.distinctBy { it.id }
	}

	private fun parseBangumiPersonVoiceWorks(
		doc: org.jsoup.nodes.Document,
	): List<BangumiPersonVoiceWorkItem> {
		return doc.select("#browserItemList > li.item, ul.browserFull > li.item, ul#browserItemList > li.item")
			.mapNotNull { item ->
				val subjectLink = item.selectFirst("a.l[href*=/subject/], a.title[href*=/subject/], h3 a[href*=/subject/]")
					?: return@mapNotNull null
				val subjectHref = subjectLink.attr("href").trim()
				val subjectId = subjectHref.substringAfter("/subject/").substringBefore('/').toLongOrNull()
					?: return@mapNotNull null
				val subjectTitle = subjectLink.attr("title").takeIf { it.isNotBlank() }
					?: subjectLink.text().trim()
				if (subjectTitle.isBlank()) {
					return@mapNotNull null
				}
				val coverUrl = item.selectFirst("img.cover, .avatarCover img, .subjectCover img")
					?.attr("src")
					?.normalizeBangumiImageUrl()
					.orEmpty()
				val characterLink = item.selectFirst("a[href*=/character/]")
				val characterName = characterLink?.attr("title")?.takeIf { it.isNotBlank() }
					?: characterLink?.text()?.trim()
				val relationship = item.selectFirst("small.grey, span.tip_j")?.text()?.trim().takeUnless { it.isNullOrBlank() }
				BangumiPersonVoiceWorkItem(
					work = ScrobblerContentInfo.RelatedWork(
						id = subjectId,
						title = subjectTitle,
						coverUrl = coverUrl,
						relationship = relationship,
						url = subjectLink.absUrl("href").ifBlank {
							if (subjectHref.startsWith("/")) bangumiWebUrl(subjectHref) else subjectHref
						},
					),
					characterName = characterName?.takeIf { it.isNotBlank() },
				)
			}
			.distinctBy { it.work.id to it.characterName }
	}

	private fun parseBangumiCharacterVoiceActors(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.PersonInfo> {
		val actors = mutableListOf<ScrobblerContentInfo.PersonInfo>()
		doc.select("a[href*=/person/]").forEach { actorLink ->
			val href = actorLink.attr("href").trim()
			if (!href.contains("/person/")) {
				return@forEach
			}
			val id = href.substringAfter("/person/").substringBefore('/').toLongOrNull()
			val name = actorLink.attr("title").takeIf { it.isNotBlank() }
				?: actorLink.text().trim()
			if (name.isBlank()) {
				return@forEach
			}
			val avatarUrl = actorLink.selectFirst("img")?.attr("src")?.normalizeBangumiImageUrl()
				?: actorLink.parent()?.selectFirst("img")?.attr("src")?.normalizeBangumiImageUrl()
			actors += ScrobblerContentInfo.PersonInfo(
				id = id,
				name = name,
				avatarUrl = avatarUrl,
				url = actorLink.absUrl("href").ifBlank {
					if (href.startsWith("/")) bangumiWebUrl(href) else href
				},
			)
		}
		return actors.distinctBy { it.id ?: it.name }
	}

	private fun parseBangumiCharacterRelatedWorks(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.RelatedWork> {
		val directSections = doc.select(".subject_section")
			.firstOrNull { section ->
				val title = section.selectFirst("h2.subtitle")?.text().orEmpty()
				title.contains("出演作品") || title.contains("相关作品") || title.contains("所属作品")
			}
		val directItems = directSections?.let(::parseBangumiSectionWorks).orEmpty()
		if (directItems.isNotEmpty()) {
			return directItems
		}
		return doc.select("#infobox a[href*=/subject/]").mapNotNull { link ->
			val href = link.attr("href").trim()
			val id = href.substringAfter("/subject/").substringBefore('/').toLongOrNull() ?: return@mapNotNull null
			val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
			ScrobblerContentInfo.RelatedWork(
				id = id,
				title = title,
				coverUrl = "",
				url = link.absUrl("href").ifBlank {
					if (href.startsWith("/")) bangumiWebUrl(href) else href
				},
			)
		}.distinctBy { it.id }
	}

	private fun parseBangumiCommentThreads(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.CommentThread> {
		return doc.select("#comment_box > .item").mapIndexedNotNull { index, item ->
			val userLink = item.selectFirst("a.avatar[href], .text > a.l[href]") ?: return@mapIndexedNotNull null
			val userName = item.selectFirst(".text > a.l")?.text()?.trim().orEmpty()
			val content = item.selectFirst("p.comment")?.text()?.trim().orEmpty()
			if (userName.isBlank() || content.isBlank()) {
				return@mapIndexedNotNull null
			}
			val avatarStyle = item.selectFirst("a.avatar .avatarNeue")?.attr("style").orEmpty()
			val avatarUrl = extractBangumiCssUrl(avatarStyle)
			val ratingClass = item.selectFirst(".starlight")?.classNames()?.firstOrNull { it.startsWith("stars") }
			val rating = ratingClass?.removePrefix("stars")?.toFloatOrNull()
			val status = item.select("small.grey").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
			val postedAt = item.select("small.grey").getOrNull(1)?.text()?.removePrefix("@")?.trim()?.takeIf { it.isNotBlank() }
			val userUrl = userLink.absUrl("href").ifBlank {
				userLink.attr("href").takeIf { it.isNotBlank() }?.let { href ->
					if (href.startsWith("/")) bangumiWebUrl(href) else href
				}.orEmpty()
			}
			ScrobblerContentInfo.CommentThread(
				id = item.attr("data-item-user").ifBlank { "comment_$index" },
				userName = userName,
				userUrl = userUrl.ifBlank { null },
				avatarUrl = avatarUrl,
				rating = rating,
				status = status,
				postedAt = postedAt,
				content = content,
			)
		}
	}

	private fun parseBangumiReviews(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.ReviewEntry> {
		val reviewsSection = doc.select(".subject_section").firstOrNull { section ->
			section.selectFirst("h2.subtitle")?.text()?.contains("评论") == true &&
				section.selectFirst("#entry_list") != null
		} ?: return emptyList()
		return reviewsSection.select("#entry_list > .item").mapIndexedNotNull { index, item ->
			val reviewLink = item.selectFirst(".entry h2.title a[href*=/blog/]") ?: return@mapIndexedNotNull null
			val href = reviewLink.attr("href").trim()
			val url = if (href.startsWith("/")) bangumiWebUrl(href) else href
			val title = reviewLink.text().trim()
			val authorLink = item.selectFirst(".tools .time a[href*=/user/]") ?: return@mapIndexedNotNull null
			val authorName = authorLink.text().trim()
			val excerpt = item.selectFirst(".content a")?.text()?.trim().orEmpty()
			if (title.isBlank() || authorName.isBlank() || excerpt.isBlank() || url.isBlank()) {
				return@mapIndexedNotNull null
			}
			val reviewId = href.substringAfter("/blog/").substringBefore('/').ifBlank { "review_$index" }
			val authorUrl = authorLink.absUrl("href").ifBlank {
				authorLink.attr("href").takeIf { it.isNotBlank() }?.let { authorHref ->
					if (authorHref.startsWith("/")) bangumiWebUrl(authorHref) else authorHref
				}.orEmpty()
			}
			val avatarUrl = item.selectFirst("p.cover img.avatarCover")?.attr("src")
				?.takeIf { it.isNotBlank() }
				?.let { if (it.startsWith("//")) "https:$it" else it }
			val timeParts = item.select(".tools .time").text()
				.split('·')
				.map { it.trim() }
				.filter { it.isNotBlank() }
			val postedAt = timeParts.getOrNull(1)
			val repliesCount = timeParts.lastOrNull()
				?.substringBefore(' ')
				?.toIntOrNull()
			ScrobblerContentInfo.ReviewEntry(
				id = reviewId,
				title = title,
				authorName = authorName,
				authorUrl = authorUrl.ifBlank { null },
				avatarUrl = avatarUrl,
				postedAt = postedAt,
				excerpt = excerpt,
				url = url,
				repliesCount = repliesCount,
			)
		}
	}

	private fun extractBangumiCssUrl(style: String): String {
		val raw = style.substringAfter("url('", "").substringBefore("')")
			.ifBlank { style.substringAfter("url(\"", "").substringBefore("\")") }
			.ifBlank { style.substringAfter("url(", "").substringBefore(")") }
			.trim()
		if (raw.isBlank()) {
			return ""
		}
		return if (raw.startsWith("//")) "https:$raw" else raw
	}

	private fun resolveBangumiContentType(
		subjectType: Int?,
		platform: String?,
		infoboxProperties: List<Pair<String, String>>,
	): ContentType? {
		return when (subjectType) {
			2 -> ContentType.VIDEO
			1 -> {
				val category = (platform ?: infoboxProperties.firstOrNull { (key, _) ->
					key.contains("类型") || key.contains("Platform", ignoreCase = true)
				}?.second).orEmpty().lowercase()
				when {
					category.contains("novel") ||
						category.contains("小说") ||
						category.contains("文库") ||
						category.contains("light novel") ||
						category.contains("lightnovel") -> ContentType.NOVEL
					else -> ContentType.MANGA
				}
			}
			else -> null
		}
	}

	private fun parseBangumiSectionWorks(
		section: org.jsoup.nodes.Element,
	): List<ScrobblerContentInfo.RelatedWork> {
		val items = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		section.select("ul.browserCoverMedium li, ul.coversSmall li").forEach { li ->
			val titleEl = li.selectFirst("a.title, p.info a, a.avatar")
			val title = titleEl?.attr("title")?.takeIf { it.isNotBlank() }
				?: titleEl?.text().orEmpty()
			val href = titleEl?.attr("href").orEmpty()
			val relId = href.substringAfter("/subject/").toLongOrNull() ?: 0L
			val bgStyle = li.selectFirst("span.coverNeue")?.attr("style").orEmpty()
			val bgUrl = bgStyle.substringAfter("url('").substringBefore("')")
			val coverUrl = if (bgUrl.startsWith("//")) "https:$bgUrl" else bgUrl
			val subtitle = li.selectFirst("span.sub, small.grey")?.text()?.trim().takeUnless { it.isNullOrBlank() }
			if (relId > 0 && title.isNotBlank()) {
				items.add(
					ScrobblerContentInfo.RelatedWork(
						id = relId,
						title = title,
						coverUrl = coverUrl,
						relationship = subtitle,
						url = bangumiWebUrl("subject/$relId"),
					),
				)
			}
		}
		return items.distinctBy { it.id }
	}

	private fun parseBangumiInfoboxProperties(
		doc: org.jsoup.nodes.Document,
	): List<Pair<String, String>> {
		val infoboxProperties = mutableListOf<Pair<String, String>>()
		doc.select("#infobox > li").forEach { li ->
			val tip = li.selectFirst("span.tip")?.text()?.trimEnd() ?: ""
			val value = li.text().removePrefix(tip).trim()
			if (tip.isNotBlank() && value.isNotBlank()) {
				infoboxProperties.add(tip.trimEnd(':').trimEnd(':', ' ') to value)
			} else if (li.text().isNotBlank()) {
				val text = li.text()
				val colonIdx = text.indexOf(':')
				if (colonIdx > 0) {
					infoboxProperties.add(text.substring(0, colonIdx).trim() to text.substring(colonIdx + 1).trim())
				}
			}
		}
		return infoboxProperties
	}

	private fun parseBangumiStaff(
		doc: org.jsoup.nodes.Document,
	): List<ScrobblerContentInfo.PersonInfo> {
		return doc.select("#infobox > li").flatMap { li ->
			val role = li.selectFirst("span.tip")
				?.text()
				?.trimEnd(':')
				?.trim()
				?.takeIf { it.isBangumiStaffRole() }
				?: return@flatMap emptyList()
			li.select("a[href*=/person/]").mapNotNull { link ->
				val href = link.attr("href").trim()
				val id = href.substringAfter("/person/").substringBefore('/').toLongOrNull()
				val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
				ScrobblerContentInfo.PersonInfo(
					id = id,
					name = name,
					url = link.absUrl("href").ifBlank {
						if (href.startsWith("/")) bangumiWebUrl(href) else href
					},
					role = role,
				)
			}
		}.distinctBy { it.id ?: it.name }
	}

	private fun String.isBangumiStaffRole(): Boolean {
		return contains("作者") ||
			contains("原作") ||
			contains("作画") ||
			contains("脚本") ||
			contains("编剧") ||
			contains("监督") ||
			contains("导演") ||
			contains("制作") ||
			contains("动画制作") ||
			contains("音乐") ||
			contains("人物设定") ||
			contains("角色设计")
	}

	private fun splitBangumiNames(raw: String): List<String> {
		return raw.split('/', '／', ',', '，', ';', '；', '\n')
			.map { it.trim() }
			.filter { it.isNotBlank() }
	}

	private suspend fun loadBangumiDocument(url: String): org.jsoup.nodes.Document {
		val request = Request.Builder()
			.url(url)
			.get()
			.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
			.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			.build()
		return Jsoup.parse(okHttp.newCall(request).await().body?.string().orEmpty())
	}

	private fun bangumiWebUrl(path: String = ""): String {
		return publicEndpoints.webBaseUrl.appendBangumiPath(path)
	}

	private fun bangumiApiUrl(path: String): String {
		return publicEndpoints.apiBaseUrl.appendBangumiPath(path)
	}

	private fun officialApiUrl(path: String): String {
		return OFFICIAL_API_URL.appendBangumiPath(path)
	}

	private fun String.appendBangumiPath(path: String): String {
		return trimEnd('/') + "/" + path.trimStart('/')
	}

	private fun normalizeBangumiBaseUrl(raw: String?, fallback: String): String {
		val value = raw?.trim().orEmpty().ifBlank { fallback }
		val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) {
			value
		} else {
			"https://$value"
		}
		return withScheme.trimEnd('/') + "/"
	}

	private fun inferBangumiApiBaseUrl(webBaseUrl: String): String {
		val uri = runCatching { URI(webBaseUrl) }.getOrNull() ?: return OFFICIAL_API_URL
		val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "https"
		val host = uri.host?.takeIf { it.isNotBlank() } ?: return OFFICIAL_API_URL
		when (host) {
			"bgmmi.anibt.net" -> return OFFICIAL_API_URL
			"bangumi.lol" -> return BANGUMI_LOL_API_URL
		}
		val apiHost = if (host.startsWith("api.")) host else "api.$host"
		return "$scheme://$apiHost/"
	}

	private fun String.normalizeBangumiImageUrl(): String {
		if (isBlank()) {
			return this
		}
		val normalized = replace(Regex("/r/\\d+x\\d+/"), "/")
			.replace("/g/", "/l/")
			.replace("/s/", "/l/")
			.replace("/m/", "/l/")
			.replace("/c/", "/l/")
		return if (normalized.startsWith("//")) "https:$normalized" else normalized
	}

	private fun mergeBangumiInfoboxProperties(
		primary: List<Pair<String, String>>,
		secondary: List<Pair<String, String>>,
	): List<Pair<String, String>> {
		val merged = LinkedHashMap<String, String>()
		(primary + secondary).forEach { (key, value) ->
			val normalizedKey = key.trim()
			val normalizedValue = value.trim()
			if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
				return@forEach
			}
			merged.putIfAbsent(normalizedKey, normalizedValue)
		}
		return merged.entries.map { it.key to it.value }
	}

	/**
	 * Sync all manga collections from Bangumi to local database.
	 * Called after authorization to pull the user's existing tracking data.
	 * Uses Bangumi API: GET /v0/users/{username}/collections?subject_type=1
	 */
	suspend fun syncLibraryFromRemote(): Int {
		val user = cachedUser ?: loadUser()
		val existingEntities = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.BANGUMI.id)
		val existingByTargetId = existingEntities.preferredScrobblingByTargetId()

		val synced = ArrayList<ScrobblingEntity>()
		
		val subjectTypesToSync = listOf(1, 2) // 1 = Book, 2 = Anime
		for (subjectType in subjectTypesToSync) {
			var offset = 0
			val limit = 50
			while (true) {
				val request = Request.Builder()
					.url(officialApiUrl("v0/users/${user.id}/collections?subject_type=$subjectType&limit=$limit&offset=$offset"))
					.cacheControl(CacheControl.FORCE_NETWORK)
					.get()
				val response = okHttp.newCall(request.build()).await().parseJson()
				val data = response.optJSONArray("data") ?: break
				if (data.length() == 0) break

				for (i in 0 until data.length()) {
					val item = data.optJSONObject(i) ?: continue
					val subjectId = item.optLong("subject_id").takeIf { it > 0 } ?: item.optJSONObject("subject")?.optLong("id") ?: continue
					val subject = item.optJSONObject("subject")
					val existing = existingByTargetId[subjectId]
					val mappedContentId = existing?.mangaId ?: 0L
					val typeInt = item.optInt("type", 0)
					val statusStr = when (typeInt) {
						1 -> "wish"
						2 -> "collect"
						3 -> "do"
						4 -> "on_hold"
						5 -> "dropped"
						else -> null
					}
					synced.add(
						ScrobblingEntity(
							scrobbler = ScrobblerService.BANGUMI.id,
							id = subjectId.toInt(),
							mangaId = mappedContentId,
							targetId = subjectId,
							status = statusStr,
							chapter = item.optInt("ep_status", 0),
							comment = item.optString("comment").takeIf { it.isNotBlank() } ?: existing?.comment.orEmpty(),
							rating = item.toBangumiCollectionRating(existing?.rating ?: 0f),
							mediaType = subjectType.toString(),
							remoteTitle = subject?.getStringOrNull("name_cn")
								?: subject?.getStringOrNull("name"),
							remoteCoverUrl = subject?.optJSONObject("images")?.let {
								it.getStringOrNull("large")
									?: it.getStringOrNull("common")
									?: it.getStringOrNull("medium")
							},
							remoteUrl = bangumiWebUrl("subject/$subjectId"),
						),
					)
				}
				offset += data.length()
				if (data.length() < limit) break
			}
		}
		val hydrated = hydrateMissingCollectionRatings(
			entities = synced,
			existingByTargetId = existingByTargetId,
		)
		val syncedIds = hydrated.mapTo(HashSet(hydrated.size)) { it.targetId }
		val preservedLocal = existingEntities.filter { it.mangaId != 0L && it.targetId !in syncedIds }

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.BANGUMI.id)
			(hydrated + preservedLocal).forEach { entity ->
				db.upsertScrobbling(entity, workResolver)
			}
		}
		return hydrated.size
	}

	private suspend fun findExistingCollection(subjectId: Long): JSONObject? = runCatching {
		val request = Request.Builder()
			.url(officialApiUrl("v0/users/-/collections/$subjectId"))
			.get()
		okHttp.newCall(request.build()).await().parseJson()
	}.getOrNull()

	private suspend fun saveCollection(json: JSONObject, mangaId: Long) {
		val subjectId = json.optLong("subject_id").takeIf { it > 0L }
			?: json.optJSONObject("subject")?.optLong("id")
			?: return
		val statusStr = when (json.optInt("type", 0)) {
			1 -> "wish"
			2 -> "collect"
			3 -> "do"
			4 -> "on_hold"
			5 -> "dropped"
			else -> null
		}
		db.upsertScrobbling(
			ScrobblingEntity(
				scrobbler = ScrobblerService.BANGUMI.id,
				id = subjectId.toInt(),
				mangaId = mangaId,
				targetId = subjectId,
				status = statusStr,
				chapter = json.optInt("ep_status", 0),
				comment = json.optString("comment", ""),
				rating = json.toBangumiCollectionRating(),
			),
			workResolver,
		)
	}

	private suspend fun hydrateMissingCollectionRatings(
		entities: List<ScrobblingEntity>,
		existingByTargetId: Map<Long, ScrobblingEntity>,
	): List<ScrobblingEntity> {
		if (entities.isEmpty()) return entities
		val refreshedByTargetId = HashMap<Long, Float>()
		entities.forEach { entity ->
			if (entity.rating > 0f) return@forEach
			val previousRating = existingByTargetId[entity.targetId]?.rating ?: 0f
			if (previousRating <= 0f) return@forEach
			val refreshedRating = findExistingCollection(entity.targetId)
				?.toBangumiCollectionRating()
				?: previousRating
			refreshedByTargetId[entity.targetId] = refreshedRating
		}
		if (refreshedByTargetId.isEmpty()) return entities
		return entities.map { entity ->
			refreshedByTargetId[entity.targetId]?.let { entity.copy(rating = it) } ?: entity
		}
	}

	private fun JSONObject.toBangumiCollectionRating(fallback: Float = 0f): Float {
		val rate = optInt("rate", 0)
		if (rate > 0) {
			return (rate.toFloat() / 10f).coerceIn(0f, 1f)
		}
		return fallback.coerceIn(0f, 1f)
	}

	private fun JSONArray?.toBangumiTags(): List<String> {
		if (this == null) return emptyList()
		val result = ArrayList<String>(length())
		for (i in 0 until length()) {
			val item = optJSONObject(i) ?: continue
			item.getStringOrNull("name")?.takeIf { it.isNotBlank() }?.let(result::add)
		}
		return result
	}

	private fun JSONArray?.toBangumiInfoboxProperties(): List<Pair<String, String>> {
		if (this == null) return emptyList()
		val result = ArrayList<Pair<String, String>>(length())
		for (i in 0 until length()) {
			val item = optJSONObject(i) ?: continue
			val key = item.getStringOrNull("key")?.trim().orEmpty()
			val value = formatBangumiInfoboxValue(item.opt("value")).orEmpty().trim()
			if (key.isNotBlank() && value.isNotBlank()) {
				result.add(key to value)
			}
		}
		return result
	}

	private fun formatBangumiInfoboxValue(value: Any?): String? = when (value) {
		null -> null
		is JSONArray -> buildList {
			for (i in 0 until value.length()) {
				when (val item = value.opt(i)) {
					is JSONObject -> {
						val key = item.getStringOrNull("k")?.trim().orEmpty()
						val nested = formatBangumiInfoboxValue(item.opt("v")).orEmpty().trim()
						when {
							key.isNotBlank() && nested.isNotBlank() -> add("$key: $nested")
							key.isNotBlank() -> add(key)
							nested.isNotBlank() -> add(nested)
						}
					}
					else -> item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
				}
			}
		}.joinToString(" / ").ifBlank { null }
		is JSONObject -> {
			val key = value.getStringOrNull("k")?.trim().orEmpty()
			val nested = formatBangumiInfoboxValue(value.opt("v")).orEmpty().trim()
			when {
				key.isNotBlank() && nested.isNotBlank() -> "$key: $nested"
				key.isNotBlank() -> key
				nested.isNotBlank() -> nested
				else -> null
			}
		}
		else -> value.toString().trim().ifBlank { null }
	}

	private data class BangumiBrowserFilters(
		val groups: List<BangumiBrowserFilterGroup>,
	)

	private data class BangumiPersonVoiceWorkItem(
		val work: ScrobblerContentInfo.RelatedWork,
		val characterName: String?,
	)

	private data class BangumiBrowserFilterGroup(
		val title: String,
		val options: List<BangumiBrowserOption>,
	)

	private data class BangumiBrowserOption(
		val title: String,
		val segment: String,
	)

	private data class BrowserTagSelection(
		val groupIndex: Int,
		val segment: String,
	)

	private data class BangumiEndpointUrls(
		val webBaseUrl: String,
		val apiBaseUrl: String,
	)

	private data class BangumiApiSubjectPayload(
		val name: String,
		val cover: String,
		val summary: String,
		val platform: String?,
		val subjectType: Int?,
		val contentType: ContentType?,
		val score: Float?,
		val rank: Int?,
		val tags: List<String>,
		val infoboxProperties: List<Pair<String, String>>,
	)

	private data class BangumiHtmlSubjectPayload(
		val name: String,
		val cover: String,
		val summary: String,
		val tags: List<String>,
		val authors: List<String>,
		val staff: List<ScrobblerContentInfo.PersonInfo>,
		val infoboxProperties: List<Pair<String, String>>,
		val episodes: List<ScrobblerContentInfo.EpisodeInfo>,
		val characters: List<ScrobblerContentInfo.CharacterInfo>,
		val commentThreads: List<ScrobblerContentInfo.CommentThread>,
		val reviews: List<ScrobblerContentInfo.ReviewEntry>,
		val relatedWorks: List<ScrobblerContentInfo.RelatedWork>,
		val recommendations: List<ScrobblerContentInfo.RelatedWork>,
		val extraSections: List<ScrobblerContentInfo.RelatedSection>,
		val actions: List<ScrobblerContentInfo.ExternalAction>,
	)

	private companion object {
		val BANGUMI_SEASONS = listOf(
			"01" to "冬",
			"04" to "春",
			"07" to "夏",
			"10" to "秋",
		)
	}
}
