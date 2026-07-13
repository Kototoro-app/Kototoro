package org.skepsun.kototoro.reader.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.parsers.model.Content

const val FULLY_READ_CHAPTER_ID = Long.MAX_VALUE

@Parcelize
data class ReaderState(
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
) : Parcelable {

	constructor(history: ContentHistory) : this(
		chapterId = history.chapterId,
		page = history.page,
		scroll = history.scroll,
	)

	constructor(manga: Content, branch: String?) : this(
		chapterId = manga.chapters?.let {
			it.firstOrNull { x -> x.branch == branch } ?: it.firstOrNull()
		}?.id ?: error("Cannot find first chapter"),
		page = 0,
		scroll = 0,
	)
}
