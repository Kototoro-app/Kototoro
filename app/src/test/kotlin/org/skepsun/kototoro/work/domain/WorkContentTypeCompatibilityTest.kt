package org.skepsun.kototoro.work.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class WorkContentTypeCompatibilityTest {

	@Test
	fun `manga subtypes belong to the same work media family`() {
		val mangaTypes = listOf(
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
		)

		mangaTypes.forEach { left ->
			mangaTypes.forEach { right ->
				assertTrue(left.isWorkContentTypeCompatibleWith(right), "$left should accept $right")
			}
		}
	}

	@Test
	fun `novel and video families remain isolated`() {
		assertTrue(ContentType.NOVEL.isWorkContentTypeCompatibleWith(ContentType.HENTAI_NOVEL))
		assertTrue(ContentType.VIDEO.isWorkContentTypeCompatibleWith(ContentType.HENTAI_VIDEO))
		assertFalse(ContentType.MANGA.isWorkContentTypeCompatibleWith(ContentType.NOVEL))
		assertFalse(ContentType.MANGA.isWorkContentTypeCompatibleWith(ContentType.VIDEO))
		assertFalse(ContentType.NOVEL.isWorkContentTypeCompatibleWith(ContentType.VIDEO))
	}

	@Test
	fun `other only accepts other and unknown types are rejected`() {
		assertTrue(ContentType.OTHER.isWorkContentTypeCompatibleWith(ContentType.OTHER))
		assertFalse(ContentType.OTHER.isWorkContentTypeCompatibleWith(ContentType.MANGA))
		assertFalse(null.isWorkContentTypeCompatibleWith(ContentType.MANGA))
		assertFalse(ContentType.MANGA.isWorkContentTypeCompatibleWith(null))
	}
}
