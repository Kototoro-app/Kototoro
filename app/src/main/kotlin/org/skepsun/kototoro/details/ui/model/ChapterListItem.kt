package org.skepsun.kototoro.details.ui.model

import android.content.res.Resources
import android.text.format.DateUtils
import org.jsoup.internal.StringUtil.StringJoiner
import org.skepsun.kototoro.core.model.getLocalizedTitle
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentChapter
import kotlin.experimental.and

data class ChapterListItem(
	val chapter: ContentChapter,
	val flags: Byte,
) : ListModel {

	private var cachedTitle: String? = null

	var description: String? = null
		private set
		get() {
			if (field != null) return field
			field = buildDescription()
			return field
		}

	var uploadDate: CharSequence? = null
		private set
		get() {
			if (field != null) return field
			if (chapter.uploadDate == 0L) return null
			field = DateUtils.getRelativeTimeSpanString(
				chapter.uploadDate,
				System.currentTimeMillis(),
				DateUtils.DAY_IN_MILLIS,
			)
			return field
		}

	val isCurrent: Boolean
		get() = hasFlag(FLAG_CURRENT)

	val isUnread: Boolean
		get() = hasFlag(FLAG_UNREAD)

	val isDownloaded: Boolean
		get() = hasFlag(FLAG_DOWNLOADED)

	val isBookmarked: Boolean
		get() = hasFlag(FLAG_BOOKMARKED)

	val isNew: Boolean
		get() = hasFlag(FLAG_NEW)

	val isGrid: Boolean
		get() = hasFlag(FLAG_GRID)

	operator fun contains(query: String): Boolean = with(chapter) {
		title?.contains(query, ignoreCase = true) == true
			|| numberString()?.contains(query) == true
			|| volumeString()?.contains(query) == true
	}

	fun getTitle(resources: Resources): String {
		cachedTitle?.let {
			return it
		}
		return chapter.getLocalizedTitle(resources).also {
			cachedTitle = it
		}
	}

	private fun buildDescription(): String {
		val joiner = StringJoiner(" • ")
		chapter.numberString()?.let {
			joiner.add("#").append(it)
		}
		uploadDate?.let { date ->
			joiner.add(date.toString())
		}
		val scanlator = chapter.scanlator?.takeIf { it.isNotBlank() } ?: chapter.branch?.takeIf { it.isNotBlank() }
		scanlator?.let {
			joiner.add(it)
		}
		return joiner.complete()
	}

	private fun hasFlag(flag: Byte): Boolean {
		return (flags and flag) == flag
	}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ChapterListItem && chapter.id == other.chapter.id
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		if (previousState !is ChapterListItem) {
			return super.getChangePayload(previousState)
		}
		return if (chapter == previousState.chapter && flags != previousState.flags) {
			flags
		} else {
			super.getChangePayload(previousState)
		}
	}

	companion object {

		const val FLAG_UNREAD: Byte = 2
		const val FLAG_CURRENT: Byte = 4
		const val FLAG_NEW: Byte = 8
		const val FLAG_BOOKMARKED: Byte = 16
		const val FLAG_DOWNLOADED: Byte = 32
		const val FLAG_GRID: Byte = 64
	}
}
