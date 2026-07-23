package org.skepsun.kototoro.reader.novel

import java.io.File

data class NovelChapterData(
	val chapterIndex: Int,
	val content: String,
	val epubFile: File?,
	val chapterPath: String?,
	val translation: NovelChapterTranslation? = null,
)
