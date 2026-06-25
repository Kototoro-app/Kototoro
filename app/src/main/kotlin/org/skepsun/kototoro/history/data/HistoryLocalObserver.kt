package org.skepsun.kototoro.history.data

import dagger.Reusable
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.LocalObserveMapper
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@Reusable
class HistoryLocalObserver @Inject constructor(
	localContentIndex: LocalContentIndex,
) : LocalObserveMapper<ContentWithHistory, ContentWithHistory>(localContentIndex) {

	fun observe(source: Flow<Collection<ContentWithHistory>>) = source.mapToLocal()

	override fun toContent(e: ContentWithHistory) = e.manga

	override fun toResult(e: ContentWithHistory, manga: Content) = ContentWithHistory(
		manga = manga,
		history = e.history,
		entityId = e.entityId,
		preferredLocalMangaId = e.preferredLocalMangaId,
	)
}
