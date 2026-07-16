package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentType

fun ContentChapter.getMergeKey(): String {
	return if (number > 0f) {
		val volKey = if (volume > 0) volume else null
		"num_${volKey}_${number}"
	} else {
		val titleKey = title?.lowercase()?.trim()
		if (!titleKey.isNullOrBlank()) {
			"title_$titleKey"
		} else {
			"unique_${id}_${url}"
		}
	}
}

fun List<ContentChapter>.mergeRepeated(): List<ContentChapter> {
	if (this.isEmpty()) return this
	val groups = this.groupBy { it.getMergeKey() }
	return groups.map { (_, groupList) ->
		if (groupList.size == 1) {
			groupList.first()
		} else {
			groupList.maxWithOrNull(
				compareBy<ContentChapter> { it.uploadDate }
					.thenBy { it.url.startsWith("file:") || it.url.startsWith("zip:") || it.url.startsWith("file+zip:") || it.url.startsWith("content:") || it.url.startsWith("epub:") || it.url.startsWith("localepub:") }
					.thenBy { it.scanlator?.isNotBlank() == true }
					.thenBy { it.title?.length ?: 0 }
			) ?: groupList.first()
		}
	}
}

fun ContentType?.isManga(): Boolean {
	this ?: return true
	return this != ContentType.VIDEO &&
		this != ContentType.HENTAI_VIDEO &&
		this != ContentType.NOVEL &&
		this != ContentType.HENTAI_NOVEL
}
