package org.skepsun.kototoro.suggestions.domain

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTagsList
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.suggestions.data.SuggestionEntity
import org.skepsun.kototoro.suggestions.data.SuggestionWithContent
import javax.inject.Inject

class SuggestionRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	fun observeAll(): Flow<List<Content>> {
		return db.getSuggestionDao().observeAll().mapItems {
			it.toContent()
		}
	}

	fun observeAll(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<Content>> {
		return db.getSuggestionDao().observeAll(limit, filterOptions).mapItems {
			it.toContent()
		}
	}

	fun observeCount(): Flow<Int> {
		return db.getSuggestionDao().observeCount()
	}

	suspend fun getRandomList(limit: Int): List<Content> {
		return db.getSuggestionDao().getRandom(limit).map {
			it.toContent()
		}
	}

	suspend fun clear() {
		db.getSuggestionDao().deleteAll()
	}

	suspend fun isEmpty(): Boolean {
		return db.getSuggestionDao().count() == 0
	}

	suspend fun getTopTags(limit: Int): List<ContentTag> {
		return db.getSuggestionDao().getTopTags(limit)
			.toContentTagsList()
	}

	suspend fun getTopSources(limit: Int): List<ContentSource> {
		return db.getSuggestionDao().getTopSources(limit)
			.toContentSources()
	}

	suspend fun replace(suggestions: Iterable<ContentSuggestion>) {
		db.withTransaction {
			db.getSuggestionDao().deleteAll()
			suggestions.forEach { (manga, relevance) ->
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				db.getSuggestionDao().upsert(
					SuggestionEntity(
						mangaId = manga.id,
						relevance = relevance,
						createdAt = System.currentTimeMillis(),
					),
				)
			}
		}
	}

	private fun SuggestionWithContent.toContent() = manga.toContent(emptySet(), null)
}
