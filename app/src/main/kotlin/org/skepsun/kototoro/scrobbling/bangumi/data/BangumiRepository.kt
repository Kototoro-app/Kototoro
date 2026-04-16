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
import org.skepsun.kototoro.core.util.ext.ensureSuccess
import org.skepsun.kototoro.core.util.ext.parseJsonOrNull
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.parseJson
import org.jsoup.Jsoup
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

private const val REDIRECT_URI = "kototoro://bangumi-auth"
private const val BASE_URL = "https://bangumi.tv/"
private const val API_URL = "https://api.bgm.tv/"

@Singleton
class BangumiRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.BANGUMI) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.BANGUMI) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.bangumi_clientId)
	private val clientSecret = context.getString(R.string.bangumi_clientSecret)
	private val browserFiltersCache = ConcurrentHashMap<String, BangumiBrowserFilters>()

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
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
			.url("${BASE_URL}oauth/access_token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder()
			.url("${API_URL}v0/me")
			.get()
		val jo = okHttp.newCall(request.build()).await().parseJson()
		return ScrobblerUser(
			id = jo.getLong("id"),
			nickname = jo.getString("nickname"),
			avatar = jo.getJSONObject("avatar").getStringOrNull("medium"),
			service = ScrobblerService.BANGUMI,
		).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun unregister(mangaId: Long) {
		db.getScrobblingDao().delete(ScrobblerService.BANGUMI.id, mangaId)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val requestBody = JSONObject().apply {
			put("keyword", query)
			put("filter", JSONObject().apply {
				put("type", JSONArray().apply { put(if (isAnime) 2 else 1) }) // 2 is Anime, 1 is Book
			})
		}.toString().toRequestBody("application/json".toMediaType())

		val request = Request.Builder()
			.url("${API_URL}v0/search/subjects?limit=10&offset=$offset")
			.post(requestBody)

		val response = okHttp.newCall(request.build()).await().parseJson()
		val data = response.getJSONArray("data")
		return data.mapJSON { json ->
			ScrobblerContent(
				id = json.getLong("id"),
				name = json.getString("name_cn").ifBlank { json.getString("name") },
				altName = json.getString("name"),
				cover = json.getJSONObject("images").getString("medium"),
				url = "https://bangumi.tv/subject/${json.getLong("id")}",
				mediaType = json.optString("platform").takeIf { it.isNotBlank() },
				isBestMatch = false
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
			append("https://bangumi.tv/")
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
				url = "https://bangumi.tv/subject/$id",
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
			.url("https://bangumi.tv/")
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
					url = "https://bangumi.tv/subject/$id",
					isBestMatch = false
				)
			}.filter { it.id > 0L }.distinctBy { it.id }
			
			map[dayInt] = items
		}
		return map
	}

private suspend fun loadBrowserFilters(category: String): BangumiBrowserFilters {
		val request = Request.Builder()
			.url("https://bangumi.tv/${getBrowserPath(category)}")
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
		db.getScrobblingDao().upsert(
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
		db.getScrobblingDao().delete(ScrobblerService.BANGUMI.id, mangaId)
		throw IOException("Bangumi collection for subject $scrobblerContentId was not created remotely")
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val entity = db.getScrobblingDao().find(ScrobblerService.BANGUMI.id, mangaId) ?: return
		updateCollection(entity.targetId, null, null, null, chapter)
		db.getScrobblingDao().upsert(entity.copy(chapter = chapter))
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val entity = db.getScrobblingDao().find(ScrobblerService.BANGUMI.id, mangaId) ?: return
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
		db.getScrobblingDao().upsert(entity.copy(
			status = status ?: entity.status,
			rating = rating,
			comment = comment ?: entity.comment
		))
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
			.url("${API_URL}v0/users/-/collections/$subjectId")
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
		val primaryName = apiPayload.name.ifBlank { htmlPayload?.name ?: "Unknown" }
		// Collect all title variants for cross-language entity normalization
		val altTitles = buildList {
			addAll(apiPayload.altNames)
			htmlPayload?.altNames?.let(::addAll)
		}.filter { it.isNotBlank() && it != primaryName }.distinct()
		return ScrobblerContentInfo(
			id = id,
			name = primaryName,
			altTitles = altTitles,
			cover = apiPayload.cover.ifBlank { htmlPayload?.cover.orEmpty() },
			url = "https://bangumi.tv/subject/$id",
			descriptionHtml = apiPayload.summary.ifBlank { htmlPayload?.summary.orEmpty() },
			tags = if (apiPayload.tags.isNotEmpty()) apiPayload.tags else htmlPayload?.tags.orEmpty(),
			authors = htmlPayload?.authors.orEmpty(),
			infoboxProperties = if (apiPayload.infoboxProperties.isNotEmpty()) {
				apiPayload.infoboxProperties
			} else {
				htmlPayload?.infoboxProperties.orEmpty()
			},
			episodes = htmlPayload?.episodes.orEmpty(),
			relatedWorks = htmlPayload?.relatedWorks.orEmpty(),
			recommendations = htmlPayload?.recommendations.orEmpty(),
		)
	}

	private suspend fun getSubjectDetailsFromApi(id: Long): BangumiApiSubjectPayload {
		val request = Request.Builder()
			.url("${API_URL}v0/subjects/$id")
			.get()
		val json = okHttp.newCall(request.build()).await().parseJson()
		val platformType = json.optString("platform").takeIf { it.isNotBlank() }
		val nameCn = json.getStringOrNull("name_cn").orEmpty()
		val nameOriginal = json.getStringOrNull("name").orEmpty()
		val primaryName = nameCn.ifBlank { nameOriginal }
		val altNames = listOfNotNull(
			nameOriginal.takeIf { it.isNotBlank() && it != primaryName },
			nameCn.takeIf { it.isNotBlank() && it != primaryName },
		)
		return BangumiApiSubjectPayload(
			name = primaryName,
			altNames = altNames,
			cover = json.optJSONObject("images")?.getStringOrNull("large")
				?: json.optJSONObject("images")?.getStringOrNull("common")
				?: json.optJSONObject("images")?.getStringOrNull("medium")
				?: "",
			summary = json.getStringOrNull("summary").orEmpty(),
			tags = json.optJSONArray("tags").toBangumiTags(),
			infoboxProperties = json.optJSONArray("infobox").toBangumiInfoboxProperties().let { list ->
				if (platformType != null && list.none { it.first == "类型" || it.first == "Platform" }) {
					listOf("类型" to platformType) + list
				} else {
					list
				}
			},
		)
	}

	private suspend fun getSubjectDetailsFromHtml(id: Long): BangumiHtmlSubjectPayload {
		val request = Request.Builder()
			.url("https://bangumi.tv/subject/$id")
			.get()
			.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
			.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			
		val responseHtml = okHttp.newCall(request.build()).await().body?.string().orEmpty()
		val doc = Jsoup.parse(responseHtml)

		val nameNative = doc.selectFirst("#headerSubject .nameSingle > a")?.text().orEmpty()
		val nameCn = doc.selectFirst("#headerSubject .nameSingle > a")?.attr("title").orEmpty()
		val finalName = if (nameCn.isNotBlank()) nameCn else nameNative
		
		val coverUrl = doc.selectFirst("img.cover")?.attr("src")?.replace("/c/", "/l/").orEmpty()
		val cover = if (coverUrl.startsWith("//")) "https:$coverUrl" else coverUrl

		val summary = doc.selectFirst("#subject_summary")?.html().orEmpty()
		
		// Real user tags from .subject_tag_section
		val tagList = mutableListOf<String>()
		doc.select(".subject_tag_section .inner a span").forEach { span ->
			val tagName = span.text().trim()
			if (tagName.isNotBlank()) tagList.add(tagName)
		}
		
		// Infobox properties as key-value pairs
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
					url = if (epUrl.startsWith("/")) "https://bangumi.tv$epUrl" else epUrl,
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
					url = "https://bangumi.tv/subject/$relId",
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
					url = "https://bangumi.tv/subject/$recId",
				))
			}
		}

		// Characters/voice actors
		val authorsList = mutableListOf<String>()
		doc.select("#browserItemList > li").forEach { charItem ->
			val charName = charItem.selectFirst("a.l")?.text().orEmpty()
			val actorName = charItem.selectFirst(".badge_actor a")?.text().orEmpty()
			if (charName.isNotBlank()) {
				if (actorName.isNotBlank()) {
					authorsList.add("$charName (CV: $actorName)")
				} else {
					authorsList.add(charName)
				}
			}
		}

		// Also collect the untranslated native name as an alternate title
		val altNames = listOfNotNull(
			nameNative.takeIf { it.isNotBlank() && it != finalName },
			nameCn.takeIf { it.isNotBlank() && it != finalName && it != nameNative },
		)

		return BangumiHtmlSubjectPayload(
			name = finalName.ifBlank { "Unknown" },
			altNames = altNames,
			cover = cover,
			summary = summary,
			tags = tagList,
			authors = authorsList,
			infoboxProperties = infoboxProperties,
			episodes = episodes,
			relatedWorks = relatedWorks,
			recommendations = recommendations,
		)
	}

	/**
	 * Sync all manga collections from Bangumi to local database.
	 * Called after authorization to pull the user's existing tracking data.
	 * Uses Bangumi API: GET /v0/users/{username}/collections?subject_type=1
	 */
	suspend fun syncLibraryFromRemote(): Int {
		val user = cachedUser ?: loadUser()
		val existingEntities = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.BANGUMI.id)
		val oldMappings = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.BANGUMI.id)
			.groupBy { it.targetId }
			.mapValues { (_, values) ->
				values.firstOrNull { it.mangaId != 0L }?.mangaId ?: 0L
			}

		val synced = ArrayList<ScrobblingEntity>()
		
		val subjectTypesToSync = listOf(1, 2) // 1 = Book, 2 = Anime
		for (subjectType in subjectTypesToSync) {
			var offset = 0
			val limit = 50
			while (true) {
				val request = Request.Builder()
					.url("${API_URL}v0/users/${user.id}/collections?subject_type=$subjectType&limit=$limit&offset=$offset")
					.cacheControl(CacheControl.FORCE_NETWORK)
					.get()
				val response = okHttp.newCall(request.build()).await().parseJson()
				val data = response.optJSONArray("data") ?: break
				if (data.length() == 0) break

				for (i in 0 until data.length()) {
					val item = data.optJSONObject(i) ?: continue
					val subjectId = item.optLong("subject_id").takeIf { it > 0 } ?: item.optJSONObject("subject")?.optLong("id") ?: continue
					val mappedContentId = oldMappings[subjectId] ?: 0L
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
							comment = item.optString("comment", ""),
							rating = (item.optInt("rate", 0).toFloat() / 10f).coerceIn(0f, 1f),
						),
					)
				}
				offset += data.length()
				if (data.length() < limit) break
			}
		}
		val syncedIds = synced.mapTo(HashSet(synced.size)) { it.targetId }
		val preservedLocal = existingEntities.filter { it.mangaId != 0L && it.targetId !in syncedIds }

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.BANGUMI.id)
			(synced + preservedLocal).forEach { entity ->
				db.getScrobblingDao().upsert(entity)
			}
		}
		return synced.size
	}

	private suspend fun findExistingCollection(subjectId: Long): JSONObject? = runCatching {
		val request = Request.Builder()
			.url("${API_URL}v0/users/-/collections/$subjectId")
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
		db.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.BANGUMI.id,
				id = subjectId.toInt(),
				mangaId = mangaId,
				targetId = subjectId,
				status = statusStr,
				chapter = json.optInt("ep_status", 0),
				comment = json.optString("comment", ""),
				rating = (json.optInt("rate", 0).toFloat() / 10f).coerceIn(0f, 1f),
			),
		)
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

	private data class BangumiApiSubjectPayload(
		val name: String,
		val altNames: List<String> = emptyList(),
		val cover: String,
		val summary: String,
		val tags: List<String>,
		val infoboxProperties: List<Pair<String, String>>,
	)

	private data class BangumiHtmlSubjectPayload(
		val name: String,
		val altNames: List<String> = emptyList(),
		val cover: String,
		val summary: String,
		val tags: List<String>,
		val authors: List<String>,
		val infoboxProperties: List<Pair<String, String>>,
		val episodes: List<ScrobblerContentInfo.EpisodeInfo>,
		val relatedWorks: List<ScrobblerContentInfo.RelatedWork>,
		val recommendations: List<ScrobblerContentInfo.RelatedWork>,
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
