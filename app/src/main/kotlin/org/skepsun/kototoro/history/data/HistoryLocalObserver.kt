package org.skepsun.kototoro.history.data

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.LocalObserveMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

@Reusable
class HistoryLocalObserver @Inject constructor(
	localContentIndex: LocalContentIndex,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) : LocalObserveMapper<ContentWithHistory, ContentWithHistory>(localContentIndex) {

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	) = observe(
		db.getHistoryDao().observeAll(order, filterOptions, limit).mapLatest { items ->
			val identitiesByMangaId = workResolver.resolveManyByMangaIds(items.map { it.manga.id })
			items.map { item ->
				val identity = identitiesByMangaId[item.manga.id]
				ContentWithHistory(
					manga = item.toContent(),
					history = item.history.toContentHistory(),
					entityId = identity?.entityId,
					preferredLocalMangaId = identity?.preferredMangaId ?: item.manga.id,
				)
			}
		},
	)

	fun observe(source: Flow<Collection<ContentWithHistory>>) = source.mapToLocal()

	override fun toContent(e: ContentWithHistory) = e.manga

	override fun toResult(e: ContentWithHistory, manga: Content) = ContentWithHistory(
		manga = manga,
		history = e.history,
		entityId = e.entityId,
		preferredLocalMangaId = e.preferredLocalMangaId,
	)

	private fun HistoryWithContent.toContent() = manga.toContent(tags.toContentTags(), null)
}
