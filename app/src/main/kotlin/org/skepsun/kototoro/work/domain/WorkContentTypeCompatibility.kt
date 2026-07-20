package org.skepsun.kototoro.work.domain

import org.skepsun.kototoro.parsers.model.ContentType

internal fun ContentType?.isWorkContentTypeCompatibleWith(other: ContentType?): Boolean {
	if (this == null || other == null) {
		return false
	}
	return workContentTypeFamily() == other.workContentTypeFamily()
}

private fun ContentType.workContentTypeFamily(): WorkContentTypeFamily = when (this) {
	ContentType.MANGA,
	ContentType.MANHWA,
	ContentType.MANHUA,
	ContentType.HENTAI_MANGA,
	ContentType.COMICS,
	ContentType.ONE_SHOT,
	ContentType.DOUJINSHI,
	ContentType.IMAGE_SET,
	ContentType.ARTIST_CG,
	ContentType.GAME_CG,
		-> WorkContentTypeFamily.MANGA

	ContentType.NOVEL,
	ContentType.HENTAI_NOVEL,
		-> WorkContentTypeFamily.NOVEL

	ContentType.VIDEO,
	ContentType.HENTAI_VIDEO,
		-> WorkContentTypeFamily.VIDEO

	ContentType.OTHER -> WorkContentTypeFamily.OTHER
}

private enum class WorkContentTypeFamily {
	MANGA,
	NOVEL,
	VIDEO,
	OTHER,
}
