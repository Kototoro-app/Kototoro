package org.skepsun.kototoro.search.domain

import android.app.SearchManager
import android.database.ContentObserver
import android.content.Context
import android.provider.SearchRecentSuggestions
import android.os.Handler
import android.os.Looper
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTag
import org.skepsun.kototoro.core.db.entity.toContentTagsList
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.search.ui.ContentSuggestionsProvider
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import kotlin.math.abs

data class LocalEntitySuggestion(
	val entityId: Long?,
	val representative: Content,
	val projectionCount: Int,
	val sourceCount: Int,
)

@Reusable
class ContentSearchRepository @Inject constructor(
	private val db: MangaDatabase,
	private val sourcesRepository: ContentSourcesRepository,
	@ApplicationContext private val context: Context,
	private val recentSuggestions: SearchRecentSuggestions,
	private val settings: AppSettings,
	private val dataRepository: ContentDataRepository,
	private val workResolver: WorkResolver,
) {

	suspend fun getContentSuggestion(query: String, limit: Int, source: ContentSource?): List<LocalEntitySuggestion> = when {
		query.isEmpty() -> db.getSuggestionDao().getTopContent(limit)
		source != null -> db.getMangaDao().searchByTitle("%$query%", source.name, limit)
		else -> db.getMangaDao().searchByTitle("%$query%", limit)
	}.let {
		if (settings.isNsfwContentDisabled) it.filterNot { x -> x.manga.isNsfw } else it
	}.map {
		it.toContent()
	}.let { contents ->
		GlobalTagBlacklist(settings.globalTagBlacklist).filter(contents)
	}.sortedBy { x ->
		x.title.levenshteinDistance(query)
	}.aggregateByEntity(limit)

	suspend fun getQuerySuggestion(
		query: String,
		limit: Int,
	): List<String> = withContext(Dispatchers.IO) {
		context.contentResolver.query(
			ContentSuggestionsProvider.QUERY_URI,
			arrayOf(SearchManager.SUGGEST_COLUMN_QUERY),
			"${SearchManager.SUGGEST_COLUMN_QUERY} LIKE ?",
			arrayOf("%$query%"),
			"date DESC",
		)?.use { cursor ->
			val count = minOf(cursor.count, limit)
			if (count == 0) {
				return@withContext emptyList()
			}
			val result = ArrayList<String>(count)
			if (cursor.moveToFirst()) {
				val index = cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_QUERY)
				do {
					result += cursor.getString(index)
				} while (currentCoroutineContext().isActive && cursor.moveToNext())
			}
			result
		}.orEmpty()
	}

	private suspend fun List<Content>.aggregateByEntity(limit: Int): List<LocalEntitySuggestion> {
		if (isEmpty()) {
			return emptyList()
		}
		val identitiesByMangaId = workResolver.resolveManyByMangaIds(map { it.id })
		val resolvedEntityIdsByMangaId = identitiesByMangaId.mapValues { it.value.entityId }.filterValues { it != null }
			.mapValues { requireNotNull(it.value) }
		val preferredLocalIdsByEntity = identitiesByMangaId.values
			.mapNotNull { identity -> identity.entityId?.let { it to identity.preferredMangaId } }
			.toMap()
		val displayTypeOrdinalByEntity = this
			.groupBy { resolvedEntityIdsByMangaId[it.id] }
			.mapNotNull { (entityId, items) ->
				entityId?.let { it to items.resolveDisplayContentTypeOrdinal() }
			}
			.toMap()
		val grouped = LinkedHashMap<String, MutableList<Content>>(size)
		val entityIdsByKey = HashMap<String, Long?>()
		forEach { content ->
			val entityId = resolvedEntityIdsByMangaId[content.id]
			val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get) ?: content.source.contentType.ordinal
			val key = entityId?.let { "entity:$it:type:$contentTypeOrdinal" } ?: "content:${content.id}"
			grouped.getOrPut(key) { ArrayList(1) } += content
			entityIdsByKey.putIfAbsent(key, entityId)
		}
		return grouped.asSequence()
			.map { (key, items) ->
				val entityId = entityIdsByKey[key]
				val preferredLocalMangaId = entityId?.let(preferredLocalIdsByEntity::get)
				LocalEntitySuggestion(
					entityId = entityId,
					representative = items.firstOrNull { it.id == preferredLocalMangaId } ?: items.first(),
					projectionCount = items.size,
					sourceCount = items.mapTo(mutableSetOf()) { it.source.name }.size,
				)
			}
			.take(limit)
			.toList()
	}

	private fun List<Content>.resolveDisplayContentTypeOrdinal(): Int {
		return firstOrNull { !it.source.name.startsWith("TRACKING_") }?.source?.contentType?.ordinal
			?: first().source.contentType.ordinal
	}

	fun observeRecentQueries(limit: Int): Flow<List<String>> = callbackFlow {
		val contentResolver = context.contentResolver
		val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
			override fun onChange(selfChange: Boolean) {
				refresh()
			}

			override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
				refresh()
			}

			fun refresh() {
				launchQuery()
			}

			private fun launchQuery() {
				kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
					trySend(
						getQuerySuggestion("", limit)
							.distinct()
							.take(limit),
					)
				}
			}
		}

		contentResolver.registerContentObserver(ContentSuggestionsProvider.QUERY_URI, true, observer)
		observer.refresh()
		awaitClose {
			contentResolver.unregisterContentObserver(observer)
		}
	}.conflate().flowOn(Dispatchers.IO)

	suspend fun getQueryHintSuggestion(
		query: String,
		limit: Int,
	): List<String> {
		if (query.isEmpty()) {
			return emptyList()
		}
		val titles = db.getSuggestionDao().getTitles("$query%")
		if (titles.isEmpty()) {
			return emptyList()
		}
		return titles.shuffled().take(limit)
	}

	suspend fun getAuthorsSuggestion(
		query: String,
		limit: Int,
	): List<String> {
		if (query.isEmpty()) {
			return emptyList()
		}
		return db.getMangaDao().findAuthors("$query%", limit)
	}

	suspend fun getTagsSuggestion(query: String, limit: Int, source: ContentSource?): List<ContentTag> {
		return when {
			query.isNotEmpty() && source != null -> db.getTagsDao()
				.findTags(source.name, "%$query%", limit)

			query.isNotEmpty() -> db.getTagsDao().findTags("%$query%", limit)
			source != null -> db.getTagsDao().findPopularTags(source.name, limit)
			else -> db.getTagsDao().findPopularTags(limit)
		}.toContentTagsList()
	}

	suspend fun getTagsSuggestion(tags: Set<ContentTag>): List<ContentTag> {
		val ids = tags.mapToSet { it.toEntity().id }
		return if (ids.size == 1) {
			db.getTagsDao().findRelatedTags(ids.first())
		} else {
			db.getTagsDao().findRelatedTags(ids)
		}.mapNotNull { x ->
			if (x.id in ids) null else x.toContentTag()
		}
	}

	suspend fun getRareTags(source: ContentSource, limit: Int): List<ContentTag> {
		return db.getTagsDao().findRareTags(source.name, limit).toContentTagsList()
	}

	suspend fun getTopTags(source: ContentSource, limit: Int): List<ContentTag> {
		return db.getTagsDao().findPopularTags(source.name, limit).toContentTagsList()
	}

	suspend fun getSourcesSuggestion(limit: Int): List<ContentSource> = sourcesRepository.getTopSources(limit)

	fun getSourcesSuggestion(query: String, limit: Int): List<ContentSource> {
		return getSourcesSuggestion(query, limit, enabledSources = emptyList())
	}

	fun getSourcesSuggestion(
		query: String,
		limit: Int,
		enabledSources: Collection<ContentSource>,
	): List<ContentSource> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty() || normalizedQuery.length < minSourcesQueryLength(normalizedQuery)) {
			return emptyList()
		}
		val skipNsfw = settings.isNsfwContentDisabled
		val queryLower = normalizedQuery.lowercase()
		val candidates = ArrayList<SourceCandidate>()

		val searchableSources = LinkedHashMap<String, ContentSource>(
			enabledSources.size + sourcesRepository.allContentSources.size,
		).also { map ->
			for (source in enabledSources) {
				map.putIfAbsent(source.name, source)
			}
			for (source in sourcesRepository.allContentSources) {
				map.putIfAbsent(source.name, source)
			}
		}.values

		for (source in searchableSources) {
			if (skipNsfw && source.isNsfw()) continue
			val title = source.getTitle(context)
			val titleLower = title.lowercase()
			val index = titleLower.indexOf(queryLower)
			if (index < 0) continue
			candidates += SourceCandidate(
				source = source,
				titleLower = titleLower,
				matchIndex = index,
				titleLength = title.length,
			)
		}
		candidates.sortWith(
			compareBy<SourceCandidate> { it.matchIndex }
				.thenBy { abs(it.titleLength - normalizedQuery.length) }
				.thenBy { it.titleLower },
		)
		val sources = candidates.map { it.source }
		return if (limit == 0) {
			sources
		} else {
			sources.take(limit)
		}
	}

	fun saveSearchQuery(query: String) {
		recentSuggestions.saveRecentQuery(query, null)
		notifySearchHistoryChanged()
	}

	suspend fun clearSearchHistory(): Unit = withContext(Dispatchers.IO) {
		recentSuggestions.clearHistory()
		notifySearchHistoryChanged()
	}

	suspend fun deleteSearchQuery(query: String) = withContext(Dispatchers.IO) {
		context.contentResolver.delete(
			ContentSuggestionsProvider.URI,
			"display1 = ?",
			arrayOf(query),
		)
		notifySearchHistoryChanged()
	}

	private fun notifySearchHistoryChanged() {
		context.contentResolver.notifyChange(ContentSuggestionsProvider.QUERY_URI, null)
	}

	suspend fun getSearchHistoryCount(): Int = withContext(Dispatchers.IO) {
		context.contentResolver.query(
			ContentSuggestionsProvider.QUERY_URI,
			arrayOf(SearchManager.SUGGEST_COLUMN_QUERY),
			null,
			arrayOfNulls(1),
			null,
		)?.use { cursor -> cursor.count } ?: 0
	}

    suspend fun getAuthors(source: ContentSource, limit: Int): List<String> {
        return db.getMangaDao().findAuthorsBySource(source.name, limit)
    }
}

private data class SourceCandidate(
	val source: ContentSource,
	val titleLower: String,
	val matchIndex: Int,
	val titleLength: Int,
)

private fun minSourcesQueryLength(query: String): Int {
	// 对 CJK/非 ASCII 查询放宽限制，避免 “漫画/日漫” 这类 2 字符关键词搜不到源
	return if (query.any { it.code > 0x7F }) 1 else 3
}
