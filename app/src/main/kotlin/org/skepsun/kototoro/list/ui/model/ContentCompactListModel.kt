package org.skepsun.kototoro.list.ui.model

import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

data class ContentCompactListModel(
	override val manga: Content,
	override val override: ContentOverride?,
	val subtitle: String?,
	val supportingText: String? = null,
	override val counter: Int,
	override val projectionCount: Int = 0,
	override val id: Long = manga.id,
	val progress: ReadingProgress? = null,
	override val isPinned: Boolean = false,
	override val metadataTrackingService: ScrobblerService? = null,
	override val scoreText: String? = null,
) : ContentListModel()
