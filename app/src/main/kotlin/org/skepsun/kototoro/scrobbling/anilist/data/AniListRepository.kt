package org.skepsun.kototoro.scrobbling.anilist.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.parsers.exception.GraphQLException
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.toIntUp
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val REDIRECT_URI = "kototoro://anilist-auth"
private const val BASE_URL = "https://anilist.co/api/v2/"
private const val ENDPOINT = "https://graphql.anilist.co"
private const val MANGA_PAGE_SIZE = 10
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"

@Singleton
class AniListRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.anilist_clientId)
	private val clientSecret = context.getString(R.string.anilist_clientSecret)

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=${REDIRECT_URI}&response_type=code"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	private val shrinkRegex = Regex("\\t+")

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
		val response = doRequest(
			REQUEST_QUERY,
			"""
			AniChartUser {
				user {
					id
					name
					avatar {
						medium
					}
					mediaListOptions {
						scoreFormat
					}
				}
			}
			""",
		)
		val jo = response.getJSONObject("data").getJSONObject("AniChartUser").getJSONObject("user")
		storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
		return AniListUser(jo).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> {
		val page = (offset / MANGA_PAGE_SIZE.toFloat()).toIntUp() + 1
		val mediaType = if (isAnime) "ANIME" else "MANGA"
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: $page, perPage: ${MANGA_PAGE_SIZE}) {
				media(type: $mediaType, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
					id
					type
					format
					title {
						userPreferred
						native
					}
					coverImage {
						medium
					}
					siteUrl
				}
			}
			""",
		)
		val data = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
		return data.mapJSON { ScrobblerContent(it, query) }
	}

	override suspend fun createRate(mangaId: Long, content: ScrobblerContent) {
		val scrobblerContentId = content.id
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: $scrobblerContentId) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, progress: $chapter) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val scoreRaw = (rating * 100f).roundToInt()
		val statusString = status?.let { ", status: $it" }.orEmpty()
		val notesString = comment?.let { ", notes: ${JSONObject.quote(it)}" }.orEmpty()
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, scoreRaw: $scoreRaw$statusString$notesString) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun getContentInfo(id: Long): ScrobblerContentInfo {
		return getMediaDetails(id)
	}

	suspend fun getAnimeInfo(id: Long): ScrobblerContentInfo {
		return getMediaDetails(id)
	}

	private suspend fun getMediaDetails(id: Long): ScrobblerContentInfo {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				id
				idMal
				title {
					userPreferred
					native
					english
				}
				coverImage {
					extraLarge
					large
				}
				description(asHtml: true)
				siteUrl
				type
				format
				status
				episodes
				chapters
				volumes
				season
				seasonYear
				meanScore
				genres
				tags {
					name
					rank
				}
				staff(perPage: 10) {
					nodes {
						name {
							full
						}
					}
					edges {
						role
					}
				}
				relations {
					edges {
						relationType
						node {
							id
							title {
								userPreferred
							}
							coverImage {
								medium
							}
							siteUrl
						}
					}
				}
				recommendations(perPage: 10, sort: RATING_DESC) {
					nodes {
						mediaRecommendation {
							id
							title {
								userPreferred
							}
							coverImage {
								medium
							}
							siteUrl
						}
					}
				}
			}
			""",
		)
		return parseMediaDetails(response.getJSONObject("data").getJSONObject("Media"))
	}

	suspend fun getTrending(mediaType: String, sort: String, page: Int, perPage: Int = 25): List<ScrobblerContent> {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: $page, perPage: $perPage) {
				media(type: $mediaType, sort: $sort) {
					id
					title {
						userPreferred
						native
					}
					coverImage {
						large
					}
					siteUrl
					meanScore
					format
					seasonYear
				}
			}
			""",
		)
		val data = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
		val results = mutableListOf<ScrobblerContent>()
		for (i in 0 until data.length()) {
			val json = data.optJSONObject(i) ?: continue
			val title = json.getJSONObject("title")
			val score = json.optInt("meanScore", 0)
			val format = json.optString("format", "").ifBlank { null }
			val year = json.optInt("seasonYear", 0).takeIf { it > 0 }?.toString()
			val subtitleParts = listOfNotNull(format, year, if (score > 0) "★${score}%" else null)
			results.add(
				ScrobblerContent(
					id = json.getLong("id"),
					name = title.getString("userPreferred"),
					altName = subtitleParts.joinToString(" · ").ifBlank { title.getStringOrNull("native") },
					cover = json.getJSONObject("coverImage").getStringOrNull("large"),
					url = json.getString("siteUrl"),
					mediaType = format?.replace("_", " "),
					isBestMatch = false,
				),
			)
		}
		return results
	}

	/**
	 * Sync all manga list entries from AniList to local database.
	 * Uses AniList GraphQL query to fetch the user's MANGA media list.
	 */
	suspend fun syncLibraryFromRemote(): Int {
		val user = cachedUser ?: loadUser()
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val oldMappings = db.getScrobblingDao()
			.findAllByScrobbler(ScrobblerService.ANILIST.id)
			.groupBy { it.targetId }
			.mapValues { (_, values) ->
				values.firstOrNull { it.mangaId != 0L }?.mangaId ?: 0L
			}

		val synced = ArrayList<ScrobblingEntity>()
		val typesToSync = listOf("MANGA", "ANIME")
		
		for (type in typesToSync) {
			var page = 1
			while (true) {
				val response = doRequest(
					REQUEST_QUERY,
					"""
					Page(page: $page, perPage: 50) {
						pageInfo {
							hasNextPage
						}
						mediaList(userId: ${user.id}, type: $type) {
							id
							mediaId
							status
							notes
							score
							progress
						}
					}
					""",
				)
				val pageData = response.getJSONObject("data").getJSONObject("Page")
				val mediaList = pageData.getJSONArray("mediaList")
				val hasNextPage = pageData.getJSONObject("pageInfo").getBoolean("hasNextPage")

				for (i in 0 until mediaList.length()) {
					val json = mediaList.optJSONObject(i) ?: continue
					val mediaId = json.optLong("mediaId", 0L)
					if (mediaId == 0L) continue
					val mappedContentId = oldMappings[mediaId] ?: 0L
					synced.add(
						ScrobblingEntity(
							scrobbler = ScrobblerService.ANILIST.id,
							id = json.getInt("id"),
							mangaId = mappedContentId,
							targetId = mediaId,
							status = json.getString("status"),
							chapter = json.getInt("progress"),
							comment = json.optString("notes", ""),
							rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
						),
					)
				}
				if (!hasNextPage) break
				page++
			}
		}

		db.withTransaction {
			db.getScrobblingDao().deleteByScrobbler(ScrobblerService.ANILIST.id)
			synced.forEach { entity ->
				db.getScrobblingDao().upsert(entity)
			}
		}
		return synced.size
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long) {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("mediaId"),
			status = json.getString("status"),
			chapter = json.getInt("progress"),
			comment = json.getString("notes"),
			rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
		)
		db.getScrobblingDao().upsert(entity)
	}

	private fun ScrobblerContent(json: JSONObject, sourceTitle: String): ScrobblerContent {
		val title = json.getJSONObject("title")
		val format = json.optString("format", "").ifBlank { json.optString("type", "") }.ifBlank { null }
		return ScrobblerContent(
			id = json.getLong("id"),
			name = title.getString("userPreferred"),
			altName = title.getStringOrNull("native"),
			cover = json.getJSONObject("coverImage").getString("medium"),
			url = json.getString("siteUrl"),
			mediaType = format?.replace("_", " "),
			isBestMatch = sourceTitle.let {
				title.keys().forEach { key ->
					if (title.getStringOrNull(key)?.equals(it, ignoreCase = true) == true) {
						return@let true
					}
				}
				false
			},
		)
	}

	private fun parseMediaDetails(json: JSONObject): ScrobblerContentInfo {
		// Genres
		val genres = mutableListOf<String>()
		json.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optString(i)?.takeIf { it.isNotBlank() }?.let(genres::add)
			}
		}
		// Tags (top 10 by rank)
		json.optJSONArray("tags")?.let { arr ->
			for (i in 0 until minOf(arr.length(), 10)) {
				arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(genres::add)
			}
		}

		// Staff
		val authors = mutableListOf<String>()
		json.optJSONObject("staff")?.let { staffObj ->
			val nodes = staffObj.optJSONArray("nodes")
			val edges = staffObj.optJSONArray("edges")
			if (nodes != null && edges != null) {
				for (i in 0 until minOf(nodes.length(), edges.length())) {
					val name = nodes.optJSONObject(i)?.optJSONObject("name")?.optString("full")
					val role = edges.optJSONObject(i)?.optString("role")
					if (!name.isNullOrBlank()) {
						authors.add(if (!role.isNullOrBlank()) "$name ($role)" else name)
					}
				}
			}
		}

		// Relations
		val relatedWorks = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		json.optJSONObject("relations")?.optJSONArray("edges")?.let { edges ->
			for (i in 0 until edges.length()) {
				val edge = edges.optJSONObject(i) ?: continue
				val node = edge.optJSONObject("node") ?: continue
				val relId = node.optLong("id", 0L)
				if (relId > 0L) {
					relatedWorks.add(ScrobblerContentInfo.RelatedWork(
						id = relId,
						title = node.optJSONObject("title")?.optString("userPreferred") ?: "Unknown",
						coverUrl = node.optJSONObject("coverImage")?.optString("medium").orEmpty(),
						relationship = edge.optString("relationType")?.takeIf { it.isNotBlank() },
						url = node.optString("siteUrl") ?: "https://anilist.co/anime/$relId",
					))
				}
			}
		}

		// Recommendations
		val recommendations = mutableListOf<ScrobblerContentInfo.RelatedWork>()
		json.optJSONObject("recommendations")?.optJSONArray("nodes")?.let { nodes ->
			for (i in 0 until nodes.length()) {
				val rec = nodes.optJSONObject(i)?.optJSONObject("mediaRecommendation") ?: continue
				val recId = rec.optLong("id", 0L)
				if (recId > 0L) {
					recommendations.add(ScrobblerContentInfo.RelatedWork(
						id = recId,
						title = rec.optJSONObject("title")?.optString("userPreferred") ?: "Unknown",
						coverUrl = rec.optJSONObject("coverImage")?.optString("medium").orEmpty(),
						url = rec.optString("siteUrl") ?: "https://anilist.co/anime/$recId",
					))
				}
			}
		}

		// Infobox
		val infobox = mutableListOf<Pair<String, String>>()
		json.optString("format").takeIf { it.isNotBlank() }?.let { infobox.add("Format" to it) }
		json.optString("status").takeIf { it.isNotBlank() }?.let { infobox.add("Status" to it) }
		json.optInt("episodes", 0).takeIf { it > 0 }?.let { infobox.add("Episodes" to it.toString()) }
		json.optInt("chapters", 0).takeIf { it > 0 }?.let { infobox.add("Chapters" to it.toString()) }
		json.optInt("volumes", 0).takeIf { it > 0 }?.let { infobox.add("Volumes" to it.toString()) }
		val score = json.optInt("meanScore", 0)
		if (score > 0) infobox.add("Score" to "$score%")
		val season = json.optString("season").takeIf { it.isNotBlank() }
		val seasonYear = json.optInt("seasonYear", 0).takeIf { it > 0 }
		if (season != null || seasonYear != null) {
			infobox.add("Season" to listOfNotNull(season, seasonYear?.toString()).joinToString(" "))
		}

		val coverImage = json.optJSONObject("coverImage")
		val cover = coverImage?.getStringOrNull("extraLarge")
			?: coverImage?.getStringOrNull("large").orEmpty()

		val titleObj = json.getJSONObject("title")
		val primaryName = titleObj.getString("userPreferred")
		val altTitles = listOfNotNull(
			titleObj.getStringOrNull("native"),
			titleObj.getStringOrNull("english"),
		).filter { it.isNotBlank() && it != primaryName }

		val externalLinks = buildMap {
			json.optLong("idMal", 0L).takeIf { it > 0L }?.let {
				put("myanimelist", it.toString())
			}
		}

		return ScrobblerContentInfo(
			id = json.getLong("id"),
			name = primaryName,
			altTitles = altTitles,
			cover = cover,
			url = json.getString("siteUrl"),
			descriptionHtml = json.optString("description", ""),
			tags = genres,
			authors = authors,
			externalLinks = externalLinks,
			infoboxProperties = infobox,
			relatedWorks = relatedWorks,
			recommendations = recommendations,
		)
	}

	@Suppress("FunctionName")
	private fun AniListUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getJSONObject("avatar").getStringOrNull("medium"),
		service = ScrobblerService.ANILIST,
	)

	private suspend fun doRequest(type: String, payload: String): JSONObject {
		val body = JSONObject()
		body.put("query", "$type { ${payload.shrink()} }")
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(ENDPOINT)
		val json = okHttp.newCall(request.build()).await().parseJson()
		json.optJSONArray("errors")?.let {
			if (it.length() != 0) {
				throw GraphQLException(it)
			}
		}
		return json
	}

	private fun String.shrink() = replace(shrinkRegex, " ")
}
