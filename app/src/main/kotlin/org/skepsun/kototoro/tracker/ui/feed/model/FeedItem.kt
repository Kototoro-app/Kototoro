package org.skepsun.kototoro.tracker.ui.feed.model

import org.skepsun.kototoro.core.model.withOverride
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty

data class FeedItem(
	val id: Long,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
	private val override: ContentOverride?,
	val manga: Content,
	val count: Int,
	val isNew: Boolean,
	val totalChapters: Int = 0,
) : ListModel {

	val imageUrl: String?
		get() = override?.coverUrl.ifNullOrEmpty { manga.coverUrl }

	val title: String
		get() = override?.title.ifNullOrEmpty { manga.title }

	fun toContentWithOverride() = manga.withOverride(override)

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FeedItem && other.id == id
	}

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is FeedItem -> null
		isNew != previousState.isNew -> ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
		else -> super.getChangePayload(previousState)
	}
}
