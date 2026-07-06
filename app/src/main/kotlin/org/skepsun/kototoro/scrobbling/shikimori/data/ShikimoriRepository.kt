package org.skepsun.kototoro.scrobbling.shikimori.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.util.ext.toRequestBody
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingTargetKey
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.preferredScrobblingByTargetAndMediaType
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

private const val DOMAIN = "shikimori.io"
private const val REDIRECT_URI = "kototoro://shikimori-auth"
private const val BASE_URL = "https://$DOMAIN/"
private const val MANGA_PAGE_SIZE = 10
private const val TARGET_TYPE_ANIME = "Anime"
private const val TARGET_TYPE_MANGA = "Manga"
private const val SHIKIMORI_DISCUSSION_FORUM = "animanga"
private const val SHIKIMORI_REVIEW_FORUM = "critiques"

@Singleton
class ShikimoriRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.SHIKIMORI) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.SHIKIMORI) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.shikimori_clientId)
	private val clientSecret = context.getString(R.string.shikimori_clientSecret)

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=$REDIRECT_URI&response_type=code&scope="

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
			.url("${BASE_URL}oauth/token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder()
			.get()
			.url("${BASE_URL}api/users/whoami")
		val rawResponse = okHttp.newCall(request.build()).await().parseRaw().trim()
		if (rawResponse.isEmpty() || rawResponse == "null") {
			throw ScrobblerAuthRequiredException(ScrobblerService.SHIKIMORI)
		}
		val response = JSONObject(rawResponse)
		return ShikimoriUser(response).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.deleteScrobblingByWorkOrManga(ScrobblerService.SHIKIMORI.id, mangaId, workResolver)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val page = offset / MANGA_PAGE_SIZE
		val pageOffset = offset % MANGA_PAGE_SIZE
		val endpoint = if (isAnime) "animes" else "mangas"
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment(endpoint)
			.addEncodedQueryParameter("page", (page + 1).toString())
			.addEncodedQueryParameter("limit", MANGA_PAGE_SIZE.toString())
			.addEncodedQueryParameter("censored", false.toString())
			.addQueryParameter("search", query)
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJsonArray()
		val list = response.mapJSON { ScrobblerContent(it, query) }
		return if (pageOffset != 0) list.drop(pageOffset) else list
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

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		val user = cachedUser ?: loadUser()
		val payload = JSONObject()
		payload.put(
			"user_rate",
			JSONObject().apply {
				put("target_id", scrobblerContentId)
				put("target_type", if (isAnimeContent(mangaId)) TARGET_TYPE_ANIME else TARGET_TYPE_MANGA)
				put("user_id", user.id)
			},
		)
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("v2")
			.addPathSegment("user_rates")
			.build()
		val request = Request.Builder().url(url).post(payload.toRequestBody()).build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val payload = JSONObject()
		payload.put(
			"user_rate",
			JSONObject().apply {
				put("chapters", chapter)
			},
		)
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("v2")
			.addPathSegment("user_rates")
			.addPathSegment(rateId.toString())
			.build()
		val request = Request.Builder().url(url).patch(payload.toRequestBody()).build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val payload = JSONObject()
		payload.put(
			"user_rate",
			JSONObject().apply {
				put("score", rating.toString())
				if (comment != null) {
					put("text", comment)
				}
				if (status != null) {
					put("status", status)
				}
			},
		)
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("v2")
			.addPathSegment("user_rates")
			.addPathSegment(rateId.toString())
			.build()
		val request = Request.Builder().url(url).patch(payload.toRequestBody()).build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId)
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		val isAnime = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.SHIKIMORI.id)
			.firstOrNull { it.targetId == id }?.let { isAnimeContent(it.mangaId) } ?: false
		return getContentInfo(id, isAnime)
	}

	suspend fun getContentInfo(id: Long, mangaId: Long): ScrobblerContentInfo {
		return getContentInfo(id, isAnimeContent(mangaId))
	}

	suspend fun getContentInfo(id: Long, mangaId: Long, mediaType: String): ScrobblerContentInfo {
		val isAnime = when {
			mediaType.equals(TARGET_TYPE_ANIME, ignoreCase = true) -> true
			mediaType.equals(TARGET_TYPE_MANGA, ignoreCase = true) -> false
			else -> isAnimeContent(mangaId)
		}
		return getContentInfo(id, isAnime)
	}

	private suspend fun getContentInfo(id: Long, isAnime: Boolean): ScrobblerContentInfo {
		if (isAnime) return getAnimeInfo(id)

		val request = Request.Builder()
			.get()
			.url("${BASE_URL}api/mangas/$id")
		val response = okHttp.newCall(request.build()).await().parseJson()
		val related = fetchRelated("mangas", id)
		val roles = fetchWorkRoles(id, isAnime = false)
		val discussion = fetchDiscussionPayload(
			linkedType = "Manga",
			linkedId = id,
			contentUrl = response.getString("url").toAbsoluteUrl(DOMAIN),
		)
		return parseDetailJson(response, related, discussion, roles = roles)
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
			EntityType.PERSON -> "people"
			EntityType.CHARACTER -> "characters"
			else -> return emptyList()
		}
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment(endpoint)
			.addQueryParameter("page", (page.coerceAtLeast(0) + 1).toString())
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("search", query)
			.build()
		val request = Request.Builder().url(url).get().build()
		return okHttp.newCall(request).await().parseJsonArray().mapJSON { json ->
			val name = json.getStringOrNull("name") ?: json.getStringOrNull("russian") ?: "Unknown"
			ScrobblerContent(
				id = json.getLong("id"),
				name = name,
				altName = json.getStringOrNull("russian")?.takeIf { !it.equals(name, ignoreCase = true) },
				cover = parseShikimoriImage(json.optJSONObject("image")),
				url = json.getString("url").toAbsoluteUrl(DOMAIN),
			)
		}.take(limit)
	}

	/**
	 * Sync all manga rates from Shikimori to local database.
	 * Uses Shikimori API: GET /api/v2/user_rates?user_id={id}&target_type=Anime|Manga
	 */
	suspend fun syncLibraryFromRemote(): Int {
		val user = cachedUser ?: loadUser()
		val oldMappings = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.SHIKIMORI.id)
			.preferredScrobblingByTargetAndMediaType()

		val synced = ArrayList<ScrobblingEntity>()
		for (targetType in listOf(TARGET_TYPE_ANIME, TARGET_TYPE_MANGA)) {
			synced += fetchUserRates(user.id, targetType, oldMappings)
		}

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.SHIKIMORI.id)
			synced.forEach { entity ->
				db.upsertScrobbling(entity, workResolver)
			}
		}
		return synced.size
	}

	private suspend fun fetchUserRates(
		userId: Long,
		targetType: String,
		oldMappings: Map<ScrobblingTargetKey, ScrobblingEntity>,
	): List<ScrobblingEntity> {
		val synced = ArrayList<ScrobblingEntity>()
		var page = 1
		val limit = 50
		while (true) {
			val url = BASE_URL.toHttpUrl().newBuilder()
				.addPathSegment("api")
				.addPathSegment("v2")
				.addPathSegment("user_rates")
				.addEncodedQueryParameter("user_id", userId.toString())
				.addEncodedQueryParameter("target_type", targetType)
				.addEncodedQueryParameter("page", page.toString())
				.addEncodedQueryParameter("limit", limit.toString())
				.build()
			val request = Request.Builder().url(url).get().build()
			val data = okHttp.newCall(request).await().parseJsonArray()
			if (data.length() == 0) break

			for (i in 0 until data.length()) {
				val json = data.optJSONObject(i) ?: continue
				val targetId = json.optLong("target_id", 0L)
				if (targetId == 0L) continue
				val mappedContentId = oldMappings[ScrobblingTargetKey(targetId, targetType)]?.mangaId
					?: oldMappings[ScrobblingTargetKey(targetId, "")]?.mangaId
					?: 0L
				synced.add(
					ScrobblingEntity(
						scrobbler = ScrobblerService.SHIKIMORI.id,
						id = json.getInt("id"),
						mangaId = mappedContentId,
						targetId = targetId,
						status = json.getString("status"),
						chapter = json.getInt("chapters"),
						comment = json.optString("text", ""),
						rating = (json.getDouble("score").toFloat() / 10f).coerceIn(0f, 1f),
						mediaType = targetType,
					),
				)
			}
			if (data.length() < limit) break
			page++
		}
		return synced
	}

	// ── Discovery API (public, no auth required) ─────────────

	/**
	 * Get anime list from Shikimori with optional filters.
	 * @param order "ranked", "popularity", "aired_on", etc.
	 * @param status "ongoing", "anons", "released", or null for all
	 * @param season e.g. "spring_2026" or null for all
	 * @param kind "tv", "movie", "ova", "ona", etc. or null for all
	 */
	suspend fun getAnimeList(
		order: String = "ranked",
		status: String? = null,
		season: String? = null,
		kind: String? = null,
		page: Int = 1,
		limit: Int = 20,
	): List<ScrobblerContent> {
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("animes")
			.addEncodedQueryParameter("page", page.toString())
			.addEncodedQueryParameter("limit", limit.toString())
			.addEncodedQueryParameter("order", order)
			.addEncodedQueryParameter("censored", "false")
			.apply {
				if (status != null) addEncodedQueryParameter("status", status)
				if (season != null) addEncodedQueryParameter("season", season)
				if (kind != null) addEncodedQueryParameter("kind", kind)
			}
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJsonArray()
		return parseShikimoriList(response, "animes")
	}

	/**
	 * Get manga list from Shikimori with optional filters.
	 */
	suspend fun getMangaList(
		order: String = "ranked",
		status: String? = null,
		kind: String? = null,
		page: Int = 1,
		limit: Int = 20,
	): List<ScrobblerContent> {
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("mangas")
			.addEncodedQueryParameter("page", page.toString())
			.addEncodedQueryParameter("limit", limit.toString())
			.addEncodedQueryParameter("order", order)
			.addEncodedQueryParameter("censored", "false")
			.apply {
				if (status != null) addEncodedQueryParameter("status", status)
				if (kind != null) addEncodedQueryParameter("kind", kind)
			}
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJsonArray()
		return parseShikimoriList(response, "mangas")
	}

	/**
	 * Search anime by text query.
	 */
	suspend fun findAnime(query: String, offset: Int): List<ScrobblerContent> {
		val page = offset / MANGA_PAGE_SIZE
		val pageOffset = offset % MANGA_PAGE_SIZE
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("animes")
			.addEncodedQueryParameter("page", (page + 1).toString())
			.addEncodedQueryParameter("limit", MANGA_PAGE_SIZE.toString())
			.addEncodedQueryParameter("censored", "false")
			.addQueryParameter("search", query)
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJsonArray()
		val list = parseShikimoriList(response, "animes")
		return if (pageOffset != 0) list.drop(pageOffset) else list
	}

	/**
	 * Get anime details from Shikimori by anime ID.
	 * Fetches the detail endpoint AND related works endpoint.
	 */
	suspend fun getAnimeInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url("${BASE_URL}api/animes/$id")
		val response = okHttp.newCall(request.build()).await().parseJson()
		val related = fetchRelated("animes", id)
		val roles = fetchWorkRoles(id, isAnime = true)
		val discussion = fetchDiscussionPayload(
			linkedType = "Anime",
			linkedId = id,
			contentUrl = response.getString("url").toAbsoluteUrl(DOMAIN),
		)
		val cover = parseShikimoriImage(response.optJSONObject("image")) ?: fetchAnimePoster(id)
		return parseDetailJson(response, related, discussion, cover, roles)
	}

	private suspend fun fetchAnimePoster(id: Long): String? {
		val body = JSONObject()
			.put(
				"query",
				"""
				{
				  animes(ids: "${id}", limit: 1) {
				    poster { mainUrl originalUrl }
				  }
				}
				""".trimIndent(),
			)
			.toRequestBody()
		val request = Request.Builder()
			.url("${BASE_URL}api/graphql")
			.post(body)
			.build()
		val json = okHttp.newCall(request).await().parseJson()
		val poster = json.optJSONObject("data")
			?.optJSONArray("animes")
			?.optJSONObject(0)
			?.optJSONObject("poster")
		return sequenceOf(
			poster?.getStringOrNull("mainUrl"),
			poster?.getStringOrNull("originalUrl"),
		).filterNotNull()
			.firstOrNull { it.isNotBlank() && !it.contains("/assets/globals/missing_", ignoreCase = true) }
	}

	private suspend fun fetchWorkRoles(id: Long, isAnime: Boolean): ShikimoriWorkRoles {
		val root = if (isAnime) "animes" else "mangas"
		val body = JSONObject()
			.put(
				"query",
				"""
				{
				  $root(ids: "${id}", limit: 1) {
				    personRoles {
				      rolesEn
				      rolesRu
				      person { id name poster { mainUrl originalUrl } }
				    }
				    characterRoles {
				      rolesEn
				      rolesRu
				      character { id name poster { mainUrl originalUrl } }
				    }
				  }
				}
				""".trimIndent(),
			)
			.toRequestBody()
		val request = Request.Builder()
			.url("${BASE_URL}api/graphql")
			.post(body)
			.build()
		return runCatching {
			val work = okHttp.newCall(request).await().parseJson()
				.optJSONObject("data")
				?.optJSONArray(root)
				?.optJSONObject(0)
				?: return@runCatching ShikimoriWorkRoles()
			ShikimoriWorkRoles(
				staff = work.optJSONArray("personRoles")
					?.mapJSONNotNull { it.toShikimoriPersonInfo() }
					.orEmpty()
					.distinctBy { it.id ?: it.name },
				characters = work.optJSONArray("characterRoles")
					?.mapJSONNotNull { it.toShikimoriCharacterInfo() }
					.orEmpty()
					.distinctBy { it.id },
			)
		}.getOrDefault(ShikimoriWorkRoles())
	}

	private suspend fun getPersonInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url("${BASE_URL}api/people/$id")
		val json = okHttp.newCall(request.build()).await().parseJson()
		val voiceRoles = json.optJSONArray("roles")?.mapJSON { role ->
			ShikimoriVoiceRole(
				characters = role.optJSONArray("characters")
					?.mapJSONNotNull { item -> parseShikimoriRelatedWork(item) }
					.orEmpty(),
				works = buildList {
					role.optJSONArray("animes")
						?.mapJSONNotNull { item -> parseShikimoriRelatedWork(item) }
						?.let(::addAll)
					role.optJSONArray("mangas")
						?.mapJSONNotNull { item -> parseShikimoriRelatedWork(item) }
						?.let(::addAll)
				},
			)
		}.orEmpty()
		val voicedWorks = voiceRoles
			.flatMap { role ->
				val characterLabel = role.characters.joinToString(", ") { it.title }
				role.works.map { work ->
					work.copy(
						relationship = characterLabel.ifBlank { work.relationship },
					)
				}
			}
			.distinctBy { it.id }
		val voicedCharacters = voiceRoles
			.flatMap { role ->
				val workLabel = role.works.joinToString(", ") { it.title }
				role.characters.map { character ->
					character.copy(
						relationship = workLabel.ifBlank { character.relationship },
					)
				}
			}
			.distinctBy { it.id }
		val staffWorks = json.optJSONArray("works")?.mapJSONNotNull { work ->
			val media = work.optJSONObject("anime") ?: work.optJSONObject("manga") ?: return@mapJSONNotNull null
			parseShikimoriRelatedWork(media)?.copy(
				relationship = work.getStringOrNull("role")?.takeIf { it.isNotBlank() },
			)
		}.orEmpty()
		val infobox = buildList {
			json.getStringOrNull("japanese")?.takeIf { it.isNotBlank() }?.let { add("Japanese" to it) }
			json.getStringOrNull("russian")?.takeIf { it.isNotBlank() }?.let { add("Russian" to it) }
			json.getStringOrNull("job_title")?.takeIf { it.isNotBlank() }?.let { add("Job" to it) }
			formatShikimoriBirthday(json.optJSONObject("birthday") ?: json.optJSONObject("birth_on"))
				?.let { add("Birthday" to it) }
			json.getStringOrNull("website")?.takeIf { it.isNotBlank() }?.let { add("Website" to it) }
			json.optJSONArray("groupped_roles")?.let { grouped ->
				for (i in 0 until grouped.length()) {
					val entry = grouped.optJSONArray(i) ?: continue
					val name = entry.optString(0).takeIf { it.isNotBlank() } ?: continue
					val count = entry.optInt(1, 0).takeIf { it > 0 } ?: continue
					add(name to count.toString())
				}
			}
		}
		return ScrobblerContentInfo(
			id = json.getLong("id"),
			name = json.getString("name"),
			cover = parseShikimoriImage(json.optJSONObject("image")).orEmpty(),
			url = json.getString("url").toAbsoluteUrl(DOMAIN),
			descriptionHtml = "",
			authors = voicedCharacters.map { it.title }.distinct(),
			infoboxProperties = infobox,
			extraSections = listOfNotNull(
				voicedWorks.takeIf { it.isNotEmpty() }?.let {
					ScrobblerContentInfo.RelatedSection(
						title = "Voiced Works",
						items = it,
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
						title = "Participated Works",
						items = it,
					)
				},
			),
			actions = listOfNotNull(
				json.optLong("topic_id", 0L).takeIf { it > 0 }?.let { topicId ->
					ScrobblerContentInfo.ExternalAction(
						title = "Discussion",
						url = "${BASE_URL}forum/topics/$topicId",
					)
				},
			),
		)
	}

	private suspend fun getCharacterInfo(id: Long): ScrobblerContentInfo {
		val request = Request.Builder()
			.get()
			.url("${BASE_URL}api/characters/$id")
		val json = okHttp.newCall(request.build()).await().parseJson()
		val relatedWorks = buildList {
			json.optJSONArray("animes")
				?.mapJSONNotNull { item -> parseShikimoriRelatedWork(item) }
				?.let(::addAll)
			json.optJSONArray("mangas")
				?.mapJSONNotNull { item -> parseShikimoriRelatedWork(item) }
				?.let(::addAll)
		}.distinctBy { it.id }
		val voiceActors = json.optJSONArray("seyu")?.mapJSON { actor ->
			val url = actor.getString("url").toAbsoluteUrl(DOMAIN)
			ScrobblerContentInfo.PersonInfo(
				id = actor.getLong("id"),
				name = actor.getString("name"),
				avatarUrl = parseShikimoriImage(actor.optJSONObject("image")),
				url = url,
			)
		}.orEmpty()
		val infobox = buildList {
			json.getStringOrNull("altname")?.takeIf { it.isNotBlank() }?.let { add("Alt name" to it) }
			json.getStringOrNull("japanese")?.takeIf { it.isNotBlank() }?.let { add("Japanese" to it) }
			json.getStringOrNull("description_source")?.takeIf { it.isNotBlank() }?.let { add("Source" to it) }
		}
		return ScrobblerContentInfo(
			id = json.getLong("id"),
			name = json.getString("name"),
			cover = parseShikimoriImage(json.optJSONObject("image")).orEmpty(),
			url = json.getString("url").toAbsoluteUrl(DOMAIN),
			descriptionHtml = json.optString("description_html", ""),
			authors = voiceActors.map { it.name },
			infoboxProperties = infobox,
			relatedWorks = relatedWorks,
			extraSections = listOfNotNull(
				voiceActors.takeIf { it.isNotEmpty() }?.let { actors ->
					ScrobblerContentInfo.RelatedSection(
						title = "Voice Actors",
						items = actors.map { actor ->
							ScrobblerContentInfo.RelatedWork(
								id = actor.id ?: 0L,
								title = actor.name,
								coverUrl = actor.avatarUrl.orEmpty(),
								url = actor.url.orEmpty(),
							)
						},
					)
				},
			),
			actions = listOfNotNull(
				json.optLong("topic_id", 0L).takeIf { it > 0 }?.let { topicId ->
					ScrobblerContentInfo.ExternalAction(
						title = "Discussion",
						url = "${BASE_URL}forum/topics/$topicId",
					)
				},
			),
		)
	}

	private suspend fun fetchDiscussionPayload(
		linkedType: String,
		linkedId: Long,
		contentUrl: String,
	): ShikimoriDiscussionPayload {
		return runCatching {
			val discussionTopics = fetchTopics(
				forum = SHIKIMORI_DISCUSSION_FORUM,
				linkedType = linkedType,
				linkedId = linkedId,
				limit = 3,
			)
			val reviewTopics = fetchTopics(
				forum = SHIKIMORI_REVIEW_FORUM,
				linkedType = linkedType,
				linkedId = linkedId,
				limit = 5,
			)
			val commentThreads = buildList {
				for (topic in discussionTopics) {
					topicToCommentThread(topic)?.let(::add)
				}
			}
			val reviews = reviewTopics.mapNotNull { topic ->
				topicToReviewEntry(
					topic = topic,
					contentUrl = contentUrl,
				)
			}
			ShikimoriDiscussionPayload(
				commentThreads = commentThreads,
				reviews = reviews,
			)
		}.getOrDefault(ShikimoriDiscussionPayload())
	}

	private suspend fun fetchTopics(
		forum: String,
		linkedType: String,
		linkedId: Long,
		limit: Int,
	): List<JSONObject> {
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("topics")
			.addEncodedQueryParameter("forum", forum)
			.addEncodedQueryParameter("linked_type", linkedType)
			.addEncodedQueryParameter("linked_id", linkedId.toString())
			.addEncodedQueryParameter("page", "1")
			.addEncodedQueryParameter("limit", limit.toString())
			.build()
		val request = Request.Builder().url(url).get().build()
		return okHttp.newCall(request).await().parseJsonArray().mapJSON { it }
	}

	private suspend fun topicToCommentThread(topic: JSONObject): ScrobblerContentInfo.CommentThread? {
		val topicId = topic.optLong("id", 0L)
		if (topicId <= 0L) {
			return null
		}
		val user = topic.optJSONObject("user")
		val replies = fetchTopicReplies(topicId)
		val content = topic.optString("body")
			.takeIf { it.isNotBlank() }
			?: topic.getStringOrNull("html_body")?.htmlToPlainText()
			?: topic.getStringOrNull("topic_title")?.takeIf { it.isNotBlank() }
		if (content.isNullOrBlank() && replies.isEmpty()) {
			return null
		}
		return ScrobblerContentInfo.CommentThread(
			id = "topic_$topicId",
			userName = user?.getStringOrNull("nickname").orEmpty().ifBlank { "Shikimori" },
			userUrl = user?.getStringOrNull("url")?.toAbsoluteUrl(DOMAIN),
			avatarUrl = user?.getStringOrNull("avatar")?.toAbsoluteUrl(DOMAIN),
			status = topic.optJSONObject("forum")?.getStringOrNull("name"),
			postedAt = topic.getStringOrNull("created_at"),
			content = content.orEmpty().ifBlank { "Topic #$topicId" },
			replies = replies,
		)
	}

	private suspend fun fetchTopicReplies(topicId: Long): List<ScrobblerContentInfo.CommentReply> {
		val url = BASE_URL.toHttpUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("comments")
			.addEncodedQueryParameter("commentable_id", topicId.toString())
			.addEncodedQueryParameter("commentable_type", "Topic")
			.addEncodedQueryParameter("page", "1")
			.addEncodedQueryParameter("limit", "10")
			.addEncodedQueryParameter("desc", "0")
			.build()
		val request = Request.Builder().url(url).get().build()
		return runCatching {
			okHttp.newCall(request).await().parseJsonArray().mapJSON { json ->
				val user = json.optJSONObject("user")
				ScrobblerContentInfo.CommentReply(
					id = json.optLong("id", 0L).takeIf { it > 0L }?.toString()
						?: "comment_${topicId}_${json.hashCode()}",
					userName = user?.getStringOrNull("nickname").orEmpty().ifBlank { "Shikimori" },
					userUrl = user?.getStringOrNull("url")?.toAbsoluteUrl(DOMAIN),
					avatarUrl = user?.getStringOrNull("avatar")?.toAbsoluteUrl(DOMAIN),
					postedAt = json.getStringOrNull("created_at"),
					content = json.optString("body")
						.takeIf { it.isNotBlank() }
						?: json.getStringOrNull("html_body")?.htmlToPlainText().orEmpty(),
				)
			}.filter { it.content.isNotBlank() }
		}.getOrElse { emptyList() }
	}

	private fun topicToReviewEntry(
		topic: JSONObject,
		contentUrl: String,
	): ScrobblerContentInfo.ReviewEntry? {
		val user = topic.optJSONObject("user") ?: return null
		val reviewLinked = topic.optJSONObject("linked")
		val reviewId = reviewLinked?.optLong("id", 0L)?.takeIf { it > 0L }
			?: topic.optLong("id", 0L).takeIf { it > 0L }
			?: return null
		val authorName = user.getStringOrNull("nickname").orEmpty()
		val excerpt = topic.optString("body")
			.takeIf { it.isNotBlank() }
			?: topic.getStringOrNull("html_body")?.htmlToPlainText()
			?: reviewLinked?.getStringOrNull("body")
			?: reviewLinked?.getStringOrNull("html_body")?.htmlToPlainText()
		if (authorName.isBlank() || excerpt.isNullOrBlank()) {
			return null
		}
		return ScrobblerContentInfo.ReviewEntry(
			id = reviewId.toString(),
			title = topic.getStringOrNull("topic_title")?.takeIf { it.isNotBlank() } ?: "Review #$reviewId",
			authorName = authorName,
			authorUrl = user.getStringOrNull("url")?.toAbsoluteUrl(DOMAIN),
			avatarUrl = user.getStringOrNull("avatar")?.toAbsoluteUrl(DOMAIN),
			postedAt = topic.getStringOrNull("created_at"),
			excerpt = excerpt.truncateForReviewExcerpt(),
			url = "${contentUrl.trimEnd('/')}/reviews/$reviewId",
			repliesCount = topic.optInt("comments_count", 0).takeIf { it > 0 },
		)
	}

	/**
	 * Fetch related works for an anime or manga.
	 * GET /api/{animes|mangas}/:id/related
	 */
	private suspend fun fetchRelated(mediaType: String, id: Long): List<ScrobblerContentInfo.RelatedWork> {
		val url = "${BASE_URL}api/$mediaType/$id/related"
		val request = Request.Builder().url(url).get().build()
		return runCatching {
			val array = okHttp.newCall(request).await().parseJsonArray()
			array.mapJSON { entry ->
				val relation = entry.getStringOrNull("relation") ?: ""
				// Each entry has either "anime" or "manga" object (one is null)
				val target = entry.optJSONObject("anime") ?: entry.optJSONObject("manga")
				if (target != null) {
					ScrobblerContentInfo.RelatedWork(
						id = target.getLong("id"),
						title = target.getString("name"),
						coverUrl = parseShikimoriImage(target.optJSONObject("image")).orEmpty(),
						relationship = relation.ifBlank { null },
						url = target.getString("url").toAbsoluteUrl(DOMAIN),
					)
				} else null
			}.filterNotNull()
		}.getOrElse { emptyList() }
	}

	/**
	 * Parse a detailed Shikimori JSON response into ScrobblerContentInfo.
	 * Extracts: genres (as tags), infobox properties, description, related works.
	 */
	private fun parseDetailJson(
		json: JSONObject,
		relatedWorks: List<ScrobblerContentInfo.RelatedWork>,
		discussion: ShikimoriDiscussionPayload = ShikimoriDiscussionPayload(),
		coverOverride: String? = null,
		roles: ShikimoriWorkRoles = ShikimoriWorkRoles(),
	): ScrobblerContentInfo {
		// Genres as tags
		val genres = json.optJSONArray("genres")
		val tags = mutableListOf<String>()
		if (genres != null) {
			for (i in 0 until genres.length()) {
				val genre = genres.optJSONObject(i)
				val name = genre?.getStringOrNull("name") ?: continue
				tags.add(name)
			}
		}

		// Infobox properties
		val infobox = mutableListOf<Pair<String, String>>()

		json.getStringOrNull("kind")?.let { kind ->
			val displayKind = kind.replace("_", " ").replaceFirstChar { it.uppercaseChar() }
			infobox.add("Type" to displayKind)
		}

		val score = json.optString("score", "")
		if (score.isNotBlank() && score != "0" && score != "0.0") {
			infobox.add("Score" to score)
		}

		json.getStringOrNull("status")?.let { status ->
			val displayStatus = status.replaceFirstChar { it.uppercaseChar() }
			infobox.add("Status" to displayStatus)
		}

		json.getStringOrNull("aired_on")?.let { infobox.add("Aired" to it) }
		json.getStringOrNull("released_on")?.let { infobox.add("Released" to it) }

		json.getStringOrNull("rating")?.let { rating ->
			val displayRating = rating.replace("_", " ").uppercase()
			infobox.add("Rating" to displayRating)
		}

		// Anime-specific fields
		val episodes = json.optInt("episodes", 0)
		val episodesAired = json.optInt("episodes_aired", 0)
		if (episodes > 0) {
			infobox.add("Episodes" to "$episodes")
		} else if (episodesAired > 0) {
			infobox.add("Episodes Aired" to "$episodesAired")
		}

		// Manga-specific fields
		val volumes = json.optInt("volumes", 0)
		val chapters = json.optInt("chapters", 0)
		if (volumes > 0) infobox.add("Volumes" to "$volumes")
		if (chapters > 0) infobox.add("Chapters" to "$chapters")

		// Studios (anime only)
		val studios = json.optJSONArray("studios")
		if (studios != null && studios.length() > 0) {
			val studioNames = mutableListOf<String>()
			for (i in 0 until studios.length()) {
				studios.optJSONObject(i)?.getStringOrNull("name")?.let(studioNames::add)
			}
			if (studioNames.isNotEmpty()) {
				infobox.add("Studio" to studioNames.joinToString(", "))
			}
		}

		val cover = coverOverride ?: parseShikimoriImage(json.optJSONObject("image")).orEmpty()

		return ScrobblerContentInfo(
			id = json.getLong("id"),
			name = json.getString("name"),
			cover = cover,
			url = json.getString("url").toAbsoluteUrl(DOMAIN),
			descriptionHtml = json.optString("description_html", ""),
			tags = tags,
			authors = roles.staff.map { it.name }.distinct(),
			staff = roles.staff,
			infoboxProperties = infobox,
			characters = roles.characters,
			commentThreads = discussion.commentThreads,
			reviews = discussion.reviews,
			relatedWorks = relatedWorks,
		)
	}

	private fun JSONObject.toShikimoriPersonInfo(): ScrobblerContentInfo.PersonInfo? {
		val person = optJSONObject("person") ?: return null
		val id = person.optLong("id", 0L).takeIf { it > 0L }
		val name = person.getStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
		return ScrobblerContentInfo.PersonInfo(
			id = id,
			name = name,
			avatarUrl = parseShikimoriPoster(person.optJSONObject("poster")),
			url = id?.let { "${BASE_URL}people/$it" },
			role = parseShikimoriRole(),
		)
	}

	private fun JSONObject.toShikimoriCharacterInfo(): ScrobblerContentInfo.CharacterInfo? {
		val character = optJSONObject("character") ?: return null
		val id = character.optLong("id", 0L)
		if (id <= 0L) {
			return null
		}
		val name = character.getStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
		return ScrobblerContentInfo.CharacterInfo(
			id = id,
			name = name,
			coverUrl = parseShikimoriPoster(character.optJSONObject("poster")).orEmpty(),
			role = parseShikimoriRole(),
			url = "${BASE_URL}characters/$id",
		)
	}

	private fun JSONObject.parseShikimoriRole(): String? {
		return sequenceOf("rolesEn", "rolesRu")
			.mapNotNull { key ->
				optJSONArray(key)
					?.let { roles ->
						buildList {
							for (i in 0 until roles.length()) {
								roles.optString(i).takeIf { it.isNotBlank() }?.let(::add)
							}
						}
					}
					?.joinToString(", ")
					?.takeIf { it.isNotBlank() }
			}
			.firstOrNull()
	}

	private fun parseShikimoriRelatedWork(json: JSONObject): ScrobblerContentInfo.RelatedWork? {
		val id = json.optLong("id", 0L)
		if (id <= 0L) {
			return null
		}
		return ScrobblerContentInfo.RelatedWork(
			id = id,
			title = json.getString("name"),
			coverUrl = parseShikimoriImage(json.optJSONObject("image")).orEmpty(),
			relationship = json.getStringOrNull("role")?.takeIf { it.isNotBlank() },
			url = json.getString("url").toAbsoluteUrl(DOMAIN),
		)
	}

	private fun parseShikimoriImage(json: JSONObject?): String? {
		return sequenceOf(
			json?.getStringOrNull("original"),
			json?.getStringOrNull("preview"),
			json?.getStringOrNull("x96"),
			json?.getStringOrNull("x48"),
		).filterNotNull()
			.firstOrNull { it.isNotBlank() && !it.contains("/assets/globals/missing_", ignoreCase = true) }
			?.toAbsoluteUrl(DOMAIN)
	}

	private fun parseShikimoriPoster(json: JSONObject?): String? {
		return sequenceOf(
			json?.getStringOrNull("mainUrl"),
			json?.getStringOrNull("originalUrl"),
		).filterNotNull()
			.firstOrNull { it.isNotBlank() && !it.contains("/assets/globals/missing_", ignoreCase = true) }
	}

	private fun formatShikimoriBirthday(json: JSONObject?): String? {
		if (json == null) {
			return null
		}
		val year = json.optInt("year", 0).takeIf { it > 0 }
		val month = json.optInt("month", 0).takeIf { it > 0 }
		val day = json.optInt("day", 0).takeIf { it > 0 }
		return listOfNotNull(
			year?.toString(),
			month?.toString()?.padStart(2, '0'),
			day?.toString()?.padStart(2, '0'),
		).takeIf { it.isNotEmpty() }?.joinToString("-")
	}

	private fun String.htmlToPlainText(): String {
		return Jsoup.parse(this)
			.text()
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun String.truncateForReviewExcerpt(maxLength: Int = 220): String {
		if (length <= maxLength) {
			return this
		}
		return take(maxLength).trimEnd() + "…"
	}

	private fun parseShikimoriList(array: org.json.JSONArray, mediaType: String): List<ScrobblerContent> {
		return array.mapJSON { json ->
			createDiscoveryContent(json, mediaType)
		}
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long) {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.SHIKIMORI.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("target_id"),
			status = json.getString("status"),
			chapter = json.getInt("chapters"),
			comment = json.getString("text"),
			rating = (json.getDouble("score").toFloat() / 10f).coerceIn(0f, 1f),
			mediaType = json.getStringOrNull("target_type").orEmpty(),
		)
		db.upsertScrobbling(entity, workResolver)
	}

	private fun ScrobblerContent(json: JSONObject, sourceTitle: String): ScrobblerContent {
		val mediaType = if (json.getString("url").contains("/animes/")) "animes" else "mangas"
		val content = createDiscoveryContent(json, mediaType)
		return content.copy(
			isBestMatch = sequenceOf(content.name, content.primaryTitle, content.secondaryTitle, content.altName)
				.filterNotNull()
				.any { it.equals(sourceTitle, ignoreCase = true) },
		)
	}

	private fun createDiscoveryContent(json: JSONObject, mediaType: String): ScrobblerContent {
		val primaryTitle = json.getString("name")
		val secondaryTitle = json.getStringOrNull("russian")?.takeIf { !it.equals(primaryTitle, ignoreCase = true) }
		val kind = json.optString("kind", "").ifBlank { null }?.replace("_", " ")
		val status = json.getStringOrNull("status")?.replace("_", " ")
		val progressText = if (mediaType.contains("anime")) {
			val aired = json.optInt("episodes_aired", 0)
			val total = json.optInt("episodes", 0)
			when {
				aired > 0 && total > 0 -> "EP $aired/$total"
				total > 0 -> "EP $total"
				else -> null
			}
		} else {
			json.optInt("chapters", 0).takeIf { it > 0 }?.let { "CH $it" }
		}
		return ScrobblerContent(
			id = json.getLong("id"),
			name = primaryTitle,
			altName = secondaryTitle,
			cover = parseShikimoriImage(json.optJSONObject("image")),
			url = json.getString("url").toAbsoluteUrl(DOMAIN),
			mediaType = kind,
			primaryTitle = primaryTitle,
			secondaryTitle = secondaryTitle,
			subtitle = listOfNotNull(kind, status).joinToString(" · ").ifBlank { null },
			progressText = progressText,
			updatedAtText = json.getStringOrNull("aired_on")?.takeIf { it.isNotBlank() }
				?: json.getStringOrNull("released_on")?.takeIf { it.isNotBlank() },
			score = json.optString("score").toFloatOrNull()?.takeIf { it > 0f },
			scoreMax = 10f,
				totalEpisodes = json.optInt("episodes", 0).takeIf { it > 0 } ?: json.optInt("chapters", 0).takeIf { it > 0 },
		)
	}

	@Suppress("FunctionName")
	private fun ShikimoriUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("nickname"),
		avatar = json.getStringOrNull("avatar"),
		service = ScrobblerService.SHIKIMORI,
	)

	private data class ShikimoriDiscussionPayload(
		val commentThreads: List<ScrobblerContentInfo.CommentThread> = emptyList(),
		val reviews: List<ScrobblerContentInfo.ReviewEntry> = emptyList(),
	)

	private data class ShikimoriWorkRoles(
		val staff: List<ScrobblerContentInfo.PersonInfo> = emptyList(),
		val characters: List<ScrobblerContentInfo.CharacterInfo> = emptyList(),
	)

	private data class ShikimoriVoiceRole(
		val characters: List<ScrobblerContentInfo.RelatedWork>,
		val works: List<ScrobblerContentInfo.RelatedWork>,
	)
}
