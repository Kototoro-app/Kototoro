package org.skepsun.kototoro.scrobbling.common.domain.model

import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel

data class ScrobblingInfo(
	val scrobbler: ScrobblerService,
	val entityId: Long? = null,
	val preferredLocalMangaId: Long? = null,
	val mangaId: Long,
	val targetId: Long,
	val status: ScrobblingStatus?,
	val chapter: Int,
	val comment: String?,
	val rating: Float,
	val title: String,
	val coverUrl: String,
	val description: CharSequence?,
	val externalUrl: String,
	val mediaType: String? = null,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ScrobblingInfo &&
			other.scrobbler == scrobbler &&
			other.entityId == entityId &&
			other.preferredLocalMangaId == preferredLocalMangaId &&
			other.targetId == targetId &&
			other.mangaId == mangaId &&
			other.mediaType == mediaType
	}

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is ScrobblingInfo -> null
		previousState.status != status || previousState.rating != rating -> ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
		else -> super.getChangePayload(previousState)
	}
}
