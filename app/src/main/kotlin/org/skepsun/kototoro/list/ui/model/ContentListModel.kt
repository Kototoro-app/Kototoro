package org.skepsun.kototoro.list.ui.model

import android.content.Context
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.model.withOverride
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.list.ui.ListModelDiffCallback.Companion.PAYLOAD_ANYTHING_CHANGED
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.R
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

sealed class ContentListModel : ListModel {

	abstract val override: ContentOverride?
	abstract val manga: Content
	abstract val counter: Int
	open val projectionCount: Int = 0
	open val isPinned: Boolean = false
	open val metadataTrackingService: ScrobblerService? = null
	open val scoreText: String? = null

	open val id: Long
		get() = manga.id

	val title: String
		get() = override?.title.ifNullOrEmpty { manga.title }

	val coverUrl: String?
		get() = override?.coverUrl.ifNullOrEmpty { manga.coverUrl }

	val source: ContentSource
		get() = manga.source

	fun toContentWithOverride() = manga.withOverride(override)

	open fun getSummary(context: Context): CharSequence = buildSpannedString {
		bold {
			append(manga.title)
		}
		appendLine()
		if (manga.tags.isNotEmpty()) {
			manga.tags.joinTo(this) { it.title }
			appendLine()
		}
		append(manga.source.getTitle(context))
	}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ContentListModel && other.javaClass == javaClass && id == other.id
	}

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is ContentListModel || previousState.manga != manga -> null
		previousState.counter != counter -> PAYLOAD_ANYTHING_CHANGED
		previousState.projectionCount != projectionCount -> PAYLOAD_ANYTHING_CHANGED
		else -> null
	}
}

fun ContentListModel.secondaryTitleText(): String? = when (this) {
	is ContentCompactListModel -> subtitle
	is ContentDetailedListModel -> subtitle
	is ContentGridModel -> subtitle
}

fun ContentListModel.supportingText(): String? = when (this) {
	is ContentCompactListModel -> supportingText
	is ContentDetailedListModel -> supportingText
	else -> null
}

fun ContentListModel.buildInfoText(context: Context): String? {
    val parts = mutableListOf<String>()

    manga.state?.let { state ->
        val stateText = context.getString(state.titleResId)
        if (stateText.isNotBlank()) {
            parts += stateText
        }
    }

    val chapterCount = manga.chapters?.size
    if (chapterCount != null && chapterCount > 0) {
        parts += context.getString(R.string.chapters_count_info, chapterCount)
    }

    if (manga.tags.isNotEmpty()) {
        val tagsText = manga.tags.take(3).joinToString(", ") { it.title }
        if (tagsText.isNotBlank()) {
            parts += tagsText
        }
    }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
