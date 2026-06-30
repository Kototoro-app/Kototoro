package org.skepsun.kototoro.list.ui.model

import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.ListModelDiffCallback.Companion.PAYLOAD_ANYTHING_CHANGED
import org.skepsun.kototoro.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

data class ContentDetailedListModel(
	override val manga: Content,
	override val override: ContentOverride?,
	val subtitle: String?,
	val supportingText: String? = null,
	override val counter: Int,
	override val projectionCount: Int = 0,
	override val id: Long = manga.id,
	val progress: ReadingProgress?,
	val isFavorite: Boolean,
	val isSaved: Boolean,
	val tags: List<ChipsView.ChipModel>,
	override val isPinned: Boolean = false,
	override val metadataTrackingService: ScrobblerService? = null,
	override val scoreText: String? = null,
) : ContentListModel() {

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is ContentDetailedListModel || previousState.manga != manga -> null

		previousState.progress != progress -> PAYLOAD_PROGRESS_CHANGED
		previousState.subtitle != subtitle ||
			previousState.supportingText != supportingText ||
			previousState.projectionCount != projectionCount ||
			previousState.isFavorite != isFavorite ||
			previousState.isSaved != isSaved ||
			previousState.scoreText != scoreText ||
			previousState.metadataTrackingService != metadataTrackingService -> PAYLOAD_ANYTHING_CHANGED

		else -> super.getChangePayload(previousState)
	}
}
