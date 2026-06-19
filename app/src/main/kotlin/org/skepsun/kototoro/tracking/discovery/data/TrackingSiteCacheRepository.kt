package org.skepsun.kototoro.tracking.discovery.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingSiteCacheRepository @Inject constructor(
	private val db: MangaDatabase,
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val detailsUpdates = MutableSharedFlow<Long>(
		extraBufferCapacity = 32,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	private data class CachedCategory(
		val items: List<TrackingSiteItem>,
		val timestamp: Long,
	)

	private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedCategory>()

	fun readCategoryCache(service: ScrobblerService, category: String): List<TrackingSiteItem>? {
		val key = "${service.id}_$category"
		val cached = categoryCache[key]
			?: readPersistedCategoryCache(service, key)
				?.let { restored ->
					CachedCategory(
						items = restored,
						timestamp = readPersistedTimestamp(key),
					).also { persisted ->
						categoryCache[key] = persisted
					}
				}
			?: return null
		if (System.currentTimeMillis() - cached.timestamp > CATEGORY_CACHE_TTL) {
			categoryCache.remove(key)
			clearPersistedCategoryCache(key)
			return null
		}
		return cached.items
	}

	fun saveCategoryCache(service: ScrobblerService, category: String, items: List<TrackingSiteItem>) {
		if (items.isEmpty()) return
		val key = "${service.id}_$category"
		val now = System.currentTimeMillis()
		categoryCache[key] = CachedCategory(items, now)
		persistCategoryCache(service, key, items, now)
	}

	fun clearCategoryCache(service: ScrobblerService, category: String) {
		val key = "${service.id}_$category"
		categoryCache.remove(key)
		clearPersistedCategoryCache(key)
	}

	suspend fun readTrending(service: ScrobblerService): List<TrackingSiteItem> = withContext(Dispatchers.Default) {
		db.getTrackingSiteDao()
			.findItems(service.id)
			.map { it.toTrackingItem() }
	}

	suspend fun saveTrending(items: List<TrackingSiteItem>) {
		if (items.isEmpty()) {
			return
		}
		val now = System.currentTimeMillis()
		db.withTransaction {
			val dao = db.getTrackingSiteDao()
			items.forEach { item ->
				val existing = dao.findItem(item.service.id, item.remoteId)
				dao.upsertItem(existing.mergeWith(item, cachedAt = now, updatedAt = now))
			}
		}
	}

	suspend fun readDetails(service: ScrobblerService, remoteId: Long): TrackingSiteItemDetails? = withContext(Dispatchers.Default) {
		db.getTrackingSiteDao()
			.findItem(service.id, remoteId)
			?.toTrackingDetails()
			?.mergeRichPayload(readPersistedDetailsPayload(service, remoteId))
	}

	suspend fun saveDetails(details: TrackingSiteItemDetails) {
		val now = System.currentTimeMillis()
		val payload = details.toPersistedPayload().toString()
		var shouldNotify = false
		db.withTransaction {
			val dao = db.getTrackingSiteDao()
			val existing = dao.findItem(details.service.id, details.remoteId)
			val merged = existing.mergeWith(details, cachedAt = now, updatedAt = now)
			shouldNotify = existing.isMeaningfullyDifferentFrom(merged) ||
				readPersistedPayloadRaw(detailsPayloadKey(details.service, details.remoteId)) != payload
			dao.upsertItem(merged)
		}
		persistDetailsPayload(details, payload)
		if (shouldNotify) {
			detailsUpdates.tryEmit(now)
		}
	}

	fun readEntityDetails(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
	): TrackingSiteItemDetails? {
		return readPersistedEntityDetailsPayload(service, entityType, remoteId)
			?.let(::entityPayloadToTrackingDetails)
	}

	fun saveEntityDetails(
		service: ScrobblerService,
		entityType: EntityType,
		details: TrackingSiteItemDetails,
	) {
		val payload = details.toPersistedPayload().toString()
		val key = entityDetailsPayloadKey(service, entityType, details.remoteId)
		val changed = readPersistedPayloadRaw(key) != payload
		persistEntityDetailsPayload(service, entityType, details.remoteId, payload)
		if (changed) {
			detailsUpdates.tryEmit(System.currentTimeMillis())
		}
	}

	fun observeDetailsUpdates(): Flow<Long> = detailsUpdates.asSharedFlow()

	private fun TrackingSiteItemEntity?.mergeWith(
		item: TrackingSiteItem,
		cachedAt: Long,
		updatedAt: Long,
	): TrackingSiteItemEntity {
		val existing = this
		return TrackingSiteItemEntity(
			service = item.service.id,
			remoteId = item.remoteId,
			title = item.title,
			altTitles = encodeStringList(listOfNotNull(item.altTitle).ifEmpty { existing?.altTitles?.let(::decodeStringList).orEmpty() }),
			primaryTitle = item.primaryTitle ?: existing?.primaryTitle,
			secondaryTitle = item.secondaryTitle ?: existing?.secondaryTitle,
			rating = item.score ?: existing?.rating,
			scoreMax = item.scoreMax ?: existing?.scoreMax,
			rank = existing?.rank,
			summary = item.subtitle ?: existing?.summary,
			progressText = item.progressText ?: existing?.progressText,
			updatedAtText = item.updatedAtText ?: existing?.updatedAtText,
			tags = existing?.tags,
			year = existing?.year,
			authors = existing?.authors,
			coverUrl = item.coverUrl ?: existing?.coverUrl,
			totalEpisodes = existing?.totalEpisodes,
			publishDate = existing?.publishDate,
			siteUrl = item.url ?: existing?.siteUrl,
			cachedAt = cachedAt,
			updatedAt = updatedAt,
		)
	}

	private fun TrackingSiteItemEntity?.mergeWith(
		details: TrackingSiteItemDetails,
		cachedAt: Long,
		updatedAt: Long,
	): TrackingSiteItemEntity {
		val existing = this
		return TrackingSiteItemEntity(
			service = details.service.id,
			remoteId = details.remoteId,
			title = details.title,
			altTitles = encodeStringList(listOfNotNull(details.altTitle).ifEmpty { existing?.altTitles?.let(::decodeStringList).orEmpty() }),
			primaryTitle = existing?.primaryTitle,
			secondaryTitle = existing?.secondaryTitle,
			rating = details.score ?: existing?.rating,
			scoreMax = existing?.scoreMax,
			rank = details.rank ?: existing?.rank,
			summary = details.description ?: existing?.summary,
			progressText = existing?.progressText,
			updatedAtText = existing?.updatedAtText,
			tags = encodeStringList(details.tags.ifEmpty { existing?.tags?.let(::decodeStringList).orEmpty() }),
			year = details.year ?: existing?.year,
			authors = encodeStringList(details.authors.ifEmpty { existing?.authors?.let(::decodeStringList).orEmpty() }),
			coverUrl = details.coverUrl ?: existing?.coverUrl,
			totalEpisodes = details.totalEpisodes ?: existing?.totalEpisodes,
			publishDate = existing?.publishDate,
			siteUrl = details.url ?: existing?.siteUrl,
			cachedAt = cachedAt,
			updatedAt = updatedAt,
		)
	}

	private fun TrackingSiteItemEntity?.isMeaningfullyDifferentFrom(other: TrackingSiteItemEntity): Boolean {
		if (this == null) {
			return true
		}
		return copy(
			cachedAt = 0L,
			updatedAt = 0L,
		) != other.copy(
			cachedAt = 0L,
			updatedAt = 0L,
		)
	}

	private fun TrackingSiteItemEntity.toTrackingItem(): TrackingSiteItem {
		val altTitles = decodeStringList(altTitles)
		return TrackingSiteItem(
			service = ScrobblerService.entries.first { it.id == service },
			remoteId = remoteId,
			title = title,
			altTitle = altTitles.firstOrNull(),
			primaryTitle = primaryTitle,
			secondaryTitle = secondaryTitle,
			progressText = progressText,
			updatedAtText = updatedAtText,
			coverUrl = coverUrl,
			subtitle = summary,
			score = rating,
			scoreMax = scoreMax,
			url = siteUrl,
				totalEpisodes = totalEpisodes,
		)
	}

	private fun TrackingSiteItemEntity.toTrackingDetails(): TrackingSiteItemDetails {
		return TrackingSiteItemDetails(
			service = ScrobblerService.entries.first { it.id == service },
			remoteId = remoteId,
			title = title,
			altTitle = decodeStringList(altTitles).firstOrNull(),
			coverUrl = coverUrl,
			description = summary,
			score = rating,
			rank = rank,
			tags = decodeStringList(tags),
			authors = decodeStringList(authors),
			year = year,
			totalEpisodes = totalEpisodes,
			url = siteUrl,
		)
	}

	private fun TrackingSiteItemDetails.mergeRichPayload(payload: JSONObject?): TrackingSiteItemDetails {
		if (payload == null) return this
		return copy(
			contentType = payload.optString("contentType").takeIf { it.isNotBlank() }?.let {
				runCatching { ContentType.valueOf(it) }.getOrNull()
			} ?: contentType,
			totalEpisodes = payload.takeIf { it.has("totalEpisodes") }?.optInt("totalEpisodes") ?: totalEpisodes,
			infoboxProperties = payload.optJSONArray("infoboxProperties")
				?.toStringPairs()
				?.ifEmpty { infoboxProperties }
				?: infoboxProperties,
			episodes = payload.optJSONArray("episodes")
				?.toEpisodes()
				?.ifEmpty { episodes }
				?: episodes,
			characters = payload.optJSONArray("characters")
				?.toCharacters()
				?.ifEmpty { characters }
				?: characters,
			commentThreads = payload.optJSONArray("commentThreads")
				?.toCommentThreads()
				?.ifEmpty { commentThreads }
				?: commentThreads,
			reviews = payload.optJSONArray("reviews")
				?.toReviews()
				?.ifEmpty { reviews }
				?: reviews,
			relatedWorks = payload.optJSONArray("relatedWorks")
				?.toRelatedWorks()
				?.ifEmpty { relatedWorks }
				?: relatedWorks,
			recommendations = payload.optJSONArray("recommendations")
				?.toRelatedWorks()
				?.ifEmpty { recommendations }
				?: recommendations,
			extraSections = payload.optJSONArray("extraSections")
				?.toRelatedSections()
				?.ifEmpty { extraSections }
				?: extraSections,
			actions = payload.optJSONArray("actions")
				?.toExternalActions()
				?.ifEmpty { actions }
				?: actions,
		)
	}

	private fun encodeStringList(values: List<String>): String? {
		if (values.isEmpty()) {
			return null
		}
		return JSONArray(values).toString()
	}

	private fun decodeStringList(raw: String?): List<String> {
		if (raw.isNullOrBlank()) {
			return emptyList()
		}
		return runCatching {
			JSONArray(raw).let { array ->
				buildList(array.length()) {
					for (index in 0 until array.length()) {
						val value = array.optString(index).trim()
						if (value.isNotEmpty()) {
							add(value)
						}
					}
				}
			}
		}.getOrElse { emptyList() }
	}

	private fun persistDetailsPayload(details: TrackingSiteItemDetails, payload: String = details.toPersistedPayload().toString()) {
		prefs.edit()
			.putString(detailsPayloadKey(details.service, details.remoteId), payload)
			.apply()
	}

	private fun readPersistedDetailsPayload(service: ScrobblerService, remoteId: Long): JSONObject? {
		val raw = prefs.getString(detailsPayloadKey(service, remoteId), null)?.takeIf { it.isNotBlank() } ?: return null
		return runCatching { JSONObject(raw) }.getOrNull()
	}

	private fun persistEntityDetailsPayload(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
		payload: String,
	) {
		prefs.edit()
			.putString(entityDetailsPayloadKey(service, entityType, remoteId), payload)
			.apply()
	}

	private fun readPersistedPayloadRaw(key: String): String? {
		return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
	}

	private fun readPersistedEntityDetailsPayload(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
	): JSONObject? {
		val raw = prefs.getString(entityDetailsPayloadKey(service, entityType, remoteId), null)
			?.takeIf { it.isNotBlank() }
			?: return null
		return runCatching { JSONObject(raw) }.getOrNull()
	}

	private fun entityPayloadToTrackingDetails(payload: JSONObject): TrackingSiteItemDetails? {
		val serviceId = payload.optInt("service", -1)
		val service = ScrobblerService.entries.firstOrNull { it.id == serviceId } ?: return null
		val remoteId = payload.optLong("remoteId", -1L)
		val title = payload.optString("title").trim()
		if (remoteId <= 0L || title.isEmpty()) {
			return null
		}
		return TrackingSiteItemDetails(
			service = service,
			remoteId = remoteId,
			title = title,
			altTitle = payload.optString("altTitle").nullIfBlank(),
			coverUrl = payload.optString("coverUrl").nullIfBlank(),
			contentType = payload.optString("contentType").takeIf { it.isNotBlank() }?.let {
				runCatching { ContentType.valueOf(it) }.getOrNull()
			},
			description = payload.optString("description").nullIfBlank(),
			score = payload.takeIf { it.has("score") }?.optDouble("score")?.toFloat(),
			rank = payload.takeIf { it.has("rank") }?.optInt("rank"),
			tags = payload.optJSONArray("tags")?.toSimpleStringList().orEmpty(),
			authors = payload.optJSONArray("authors")?.toSimpleStringList().orEmpty(),
			year = payload.takeIf { it.has("year") }?.optInt("year"),
			totalEpisodes = payload.takeIf { it.has("totalEpisodes") }?.optInt("totalEpisodes"),
			url = payload.optString("url").nullIfBlank(),
			infoboxProperties = payload.optJSONArray("infoboxProperties")?.toStringPairs().orEmpty(),
			episodes = payload.optJSONArray("episodes")?.toEpisodes().orEmpty(),
			characters = payload.optJSONArray("characters")?.toCharacters().orEmpty(),
			commentThreads = payload.optJSONArray("commentThreads")?.toCommentThreads().orEmpty(),
			reviews = payload.optJSONArray("reviews")?.toReviews().orEmpty(),
			relatedWorks = payload.optJSONArray("relatedWorks")?.toRelatedWorks().orEmpty(),
			recommendations = payload.optJSONArray("recommendations")?.toRelatedWorks().orEmpty(),
			extraSections = payload.optJSONArray("extraSections")?.toRelatedSections().orEmpty(),
			actions = payload.optJSONArray("actions")?.toExternalActions().orEmpty(),
		)
	}

	private fun List<TrackingSiteItemDetails.RelatedWork>.toJsonArray(): JSONArray = JSONArray().apply {
		forEach { item ->
			put(
				JSONObject().apply {
					put("id", item.id)
					put("title", item.title)
					put("coverUrl", item.coverUrl)
					put("relationship", item.relationship)
					put("url", item.url)
				},
			)
		}
	}

	private fun JSONArray.toStringPairs(): List<Pair<String, String>> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val key = item.optString("key").trim()
			val value = item.optString("value").trim()
			if (key.isNotEmpty() && value.isNotEmpty()) {
				add(key to value)
			}
		}
	}

	private fun JSONArray.toSimpleStringList(): List<String> = buildList(length()) {
		for (index in 0 until length()) {
			optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
		}
	}

	private fun JSONArray.toEpisodes(): List<TrackingSiteItemDetails.EpisodeInfo> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val url = item.optString("url").trim()
			val title = item.optString("title").trim()
			if (url.isEmpty() && title.isEmpty()) continue
			add(
				TrackingSiteItemDetails.EpisodeInfo(
					number = item.optString("number"),
					title = title,
					url = url,
				),
			)
		}
	}

	private fun JSONArray.toCharacters(): List<TrackingSiteItemDetails.CharacterInfo> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optLong("id", -1L)
			val name = item.optString("name").trim()
			if (id <= 0L || name.isEmpty()) continue
			add(
				TrackingSiteItemDetails.CharacterInfo(
					id = id,
					name = name,
					coverUrl = item.optString("coverUrl"),
					role = item.optString("role").nullIfBlank(),
					url = item.optString("url"),
					voiceActors = item.optJSONArray("voiceActors")?.toPersons().orEmpty(),
				),
			)
		}
	}

	private fun JSONArray.toPersons(): List<TrackingSiteItemDetails.PersonInfo> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val name = item.optString("name").trim()
			if (name.isEmpty()) continue
			add(
				TrackingSiteItemDetails.PersonInfo(
					id = item.takeIf { it.has("id") }?.optLong("id"),
					name = name,
					avatarUrl = item.optString("avatarUrl").nullIfBlank(),
					url = item.optString("url").nullIfBlank(),
				),
			)
		}
	}

	private fun JSONArray.toCommentThreads(): List<TrackingSiteItemDetails.CommentThread> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optString("id").trim()
			val userName = item.optString("userName").trim()
			val content = item.optString("content").trim()
			if (id.isEmpty() || userName.isEmpty() || content.isEmpty()) continue
			add(
				TrackingSiteItemDetails.CommentThread(
					id = id,
					userName = userName,
					userUrl = item.optString("userUrl").nullIfBlank(),
					avatarUrl = item.optString("avatarUrl").nullIfBlank(),
					rating = item.takeIf { it.has("rating") }?.optDouble("rating")?.toFloat(),
					status = item.optString("status").nullIfBlank(),
					postedAt = item.optString("postedAt").nullIfBlank(),
					content = content,
					replies = item.optJSONArray("replies")?.toCommentReplies().orEmpty(),
				),
			)
		}
	}

	private fun JSONArray.toCommentReplies(): List<TrackingSiteItemDetails.CommentReply> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optString("id").trim()
			val userName = item.optString("userName").trim()
			val content = item.optString("content").trim()
			if (id.isEmpty() || userName.isEmpty() || content.isEmpty()) continue
			add(
				TrackingSiteItemDetails.CommentReply(
					id = id,
					userName = userName,
					userUrl = item.optString("userUrl").nullIfBlank(),
					avatarUrl = item.optString("avatarUrl").nullIfBlank(),
					postedAt = item.optString("postedAt").nullIfBlank(),
					content = content,
				),
			)
		}
	}

	private fun JSONArray.toReviews(): List<TrackingSiteItemDetails.ReviewEntry> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optString("id").trim()
			val title = item.optString("title").trim()
			val authorName = item.optString("authorName").trim()
			val excerpt = item.optString("excerpt").trim()
			val url = item.optString("url").trim()
			if (id.isEmpty() || title.isEmpty() || authorName.isEmpty() || excerpt.isEmpty() || url.isEmpty()) continue
			add(
				TrackingSiteItemDetails.ReviewEntry(
					id = id,
					title = title,
					authorName = authorName,
					authorUrl = item.optString("authorUrl").nullIfBlank(),
					avatarUrl = item.optString("avatarUrl").nullIfBlank(),
					postedAt = item.optString("postedAt").nullIfBlank(),
					excerpt = excerpt,
					url = url,
					repliesCount = item.takeIf { it.has("repliesCount") }?.optInt("repliesCount"),
				),
			)
		}
	}

	private fun JSONArray.toRelatedWorks(): List<TrackingSiteItemDetails.RelatedWork> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val id = item.optLong("id", -1L)
			val title = item.optString("title").trim()
			if (id <= 0L || title.isEmpty()) continue
			add(
				TrackingSiteItemDetails.RelatedWork(
					id = id,
					title = title,
					coverUrl = item.optString("coverUrl"),
					relationship = item.optString("relationship").nullIfBlank(),
					url = item.optString("url"),
				),
			)
		}
	}

	private fun JSONArray.toRelatedSections(): List<TrackingSiteItemDetails.RelatedSection> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val title = item.optString("title").trim()
			if (title.isEmpty()) continue
			add(
				TrackingSiteItemDetails.RelatedSection(
					title = title,
					items = item.optJSONArray("items")?.toRelatedWorks().orEmpty(),
				),
			)
		}
	}

	private fun JSONArray.toExternalActions(): List<TrackingSiteItemDetails.ExternalAction> = buildList(length()) {
		for (index in 0 until length()) {
			val item = optJSONObject(index) ?: continue
			val title = item.optString("title").trim()
			val url = item.optString("url").trim()
			if (title.isEmpty() || url.isEmpty()) continue
			add(
				TrackingSiteItemDetails.ExternalAction(
					title = title,
					url = url,
				),
			)
		}
	}

	private fun persistCategoryCache(
		service: ScrobblerService,
		key: String,
		items: List<TrackingSiteItem>,
		timestamp: Long,
	) {
		val payload = JSONArray().apply {
			items.forEach { item ->
				put(
					JSONObject().apply {
						put("remoteId", item.remoteId)
						put("title", item.title)
						put("altTitle", item.altTitle)
						put("primaryTitle", item.primaryTitle)
						put("secondaryTitle", item.secondaryTitle)
						put("progressText", item.progressText)
						put("updatedAtText", item.updatedAtText)
						put("coverUrl", item.coverUrl)
						put("subtitle", item.subtitle)
						if (item.score != null) {
							put("score", item.score.toDouble())
						}
						if (item.scoreMax != null) {
							put("scoreMax", item.scoreMax.toDouble())
						}
						put("url", item.url)
					},
				)
			}
		}.toString()
		prefs.edit()
			.putString(categoryCacheItemsKey(key), payload)
			.putLong(categoryCacheTimestampKey(key), timestamp)
			.apply()
	}

	private fun readPersistedCategoryCache(
		service: ScrobblerService,
		key: String,
	): List<TrackingSiteItem>? {
		val payload = prefs.getString(categoryCacheItemsKey(key), null)?.takeIf { it.isNotBlank() } ?: return null
		return runCatching {
			JSONArray(payload).let { array ->
				buildList(array.length()) {
					for (index in 0 until array.length()) {
						val item = array.optJSONObject(index) ?: continue
						val remoteId = item.optLong("remoteId", -1L)
						val title = item.optString("title").trim()
						if (remoteId < 0L || title.isEmpty()) {
							continue
						}
						add(
							TrackingSiteItem(
								service = service,
								remoteId = remoteId,
								title = title,
								altTitle = item.optString("altTitle").nullIfBlank(),
								primaryTitle = item.optString("primaryTitle").nullIfBlank(),
								secondaryTitle = item.optString("secondaryTitle").nullIfBlank(),
								progressText = item.optString("progressText").nullIfBlank(),
								updatedAtText = item.optString("updatedAtText").nullIfBlank(),
								coverUrl = item.optString("coverUrl").nullIfBlank(),
								subtitle = item.optString("subtitle").nullIfBlank(),
								score = item.takeIf { it.has("score") }?.optDouble("score")?.toFloat(),
								scoreMax = item.takeIf { it.has("scoreMax") }?.optDouble("scoreMax")?.toFloat(),
								url = item.optString("url").nullIfBlank(),
								totalEpisodes = item.takeIf { it.has("totalEpisodes") }?.optInt("totalEpisodes"),
							),
						)
					}
				}
			}
		}.getOrNull()?.takeIf { it.isNotEmpty() }
	}

	private fun readPersistedTimestamp(key: String): Long {
		return prefs.getLong(categoryCacheTimestampKey(key), 0L)
	}

	private fun clearPersistedCategoryCache(key: String) {
		prefs.edit()
			.remove(categoryCacheItemsKey(key))
			.remove(categoryCacheTimestampKey(key))
			.apply()
	}

	private fun categoryCacheItemsKey(key: String): String = "category_items_$key"

	private fun categoryCacheTimestampKey(key: String): String = "category_timestamp_$key"

	private fun detailsPayloadKey(service: ScrobblerService, remoteId: Long): String = "details_payload_${service.id}_$remoteId"

	private fun entityDetailsPayloadKey(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
	): String = "entity_details_payload_${service.id}_${entityType.name}_$remoteId"

	private fun TrackingSiteItemDetails.toPersistedPayload(): JSONObject {
		return JSONObject().apply {
			put("service", service.id)
			put("remoteId", remoteId)
			put("title", title)
			put("altTitle", altTitle)
			put("coverUrl", coverUrl)
			contentType?.let { put("contentType", it.name) }
			put("description", description)
			score?.let { put("score", it.toDouble()) }
			rank?.let { put("rank", it) }
			put("tags", JSONArray(tags))
			put("authors", JSONArray(authors))
			year?.let { put("year", it) }
			totalEpisodes?.let { put("totalEpisodes", it) }
			put("url", url)
			put("infoboxProperties", JSONArray().apply {
				this@toPersistedPayload.infoboxProperties.forEach { (key, value) ->
					put(
						JSONObject().apply {
							put("key", key)
							put("value", value)
						},
					)
				}
			})
			put("episodes", JSONArray().apply {
				this@toPersistedPayload.episodes.forEach { episode ->
					put(
						JSONObject().apply {
							put("number", episode.number)
							put("title", episode.title)
							put("url", episode.url)
						},
					)
				}
			})
			put("characters", JSONArray().apply {
				this@toPersistedPayload.characters.forEach { character ->
					put(
						JSONObject().apply {
							put("id", character.id)
							put("name", character.name)
							put("coverUrl", character.coverUrl)
							put("role", character.role)
							put("url", character.url)
							put("voiceActors", JSONArray().apply {
								character.voiceActors.forEach { actor ->
									put(
										JSONObject().apply {
											actor.id?.let { put("id", it) }
											put("name", actor.name)
											put("avatarUrl", actor.avatarUrl)
											put("url", actor.url)
										},
									)
								}
							})
						},
					)
				}
			})
			put("commentThreads", JSONArray().apply {
				this@toPersistedPayload.commentThreads.forEach { thread ->
					put(
						JSONObject().apply {
							put("id", thread.id)
							put("userName", thread.userName)
							put("userUrl", thread.userUrl)
							put("avatarUrl", thread.avatarUrl)
							thread.rating?.let { put("rating", it.toDouble()) }
							put("status", thread.status)
							put("postedAt", thread.postedAt)
							put("content", thread.content)
							put("replies", JSONArray().apply {
								thread.replies.forEach { reply ->
									put(
										JSONObject().apply {
											put("id", reply.id)
											put("userName", reply.userName)
											put("userUrl", reply.userUrl)
											put("avatarUrl", reply.avatarUrl)
											put("postedAt", reply.postedAt)
											put("content", reply.content)
										},
									)
								}
							})
						},
					)
				}
			})
			put("reviews", JSONArray().apply {
				this@toPersistedPayload.reviews.forEach { review ->
					put(
						JSONObject().apply {
							put("id", review.id)
							put("title", review.title)
							put("authorName", review.authorName)
							put("authorUrl", review.authorUrl)
							put("avatarUrl", review.avatarUrl)
							put("postedAt", review.postedAt)
							put("excerpt", review.excerpt)
							put("url", review.url)
							review.repliesCount?.let { put("repliesCount", it) }
						},
					)
				}
			})
			put("relatedWorks", this@toPersistedPayload.relatedWorks.toJsonArray())
			put("recommendations", this@toPersistedPayload.recommendations.toJsonArray())
			put("extraSections", JSONArray().apply {
				this@toPersistedPayload.extraSections.forEach { section ->
					put(
						JSONObject().apply {
							put("title", section.title)
							put("items", section.items.toJsonArray())
						},
					)
				}
			})
			put("actions", JSONArray().apply {
				this@toPersistedPayload.actions.forEach { action ->
					put(
						JSONObject().apply {
							put("title", action.title)
							put("url", action.url)
						},
					)
				}
			})
		}
	}

	private fun String.nullIfBlank(): String? = takeIf { it.isNotBlank() }

	companion object {
		private const val PREFS_NAME = "tracking_site_discovery_cache"
		private const val CATEGORY_CACHE_TTL = 12 * 60 * 60 * 1000L // 12 hours
	}
}
