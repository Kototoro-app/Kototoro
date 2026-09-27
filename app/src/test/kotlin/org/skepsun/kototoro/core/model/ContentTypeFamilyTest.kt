package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class ContentTypeFamilyTest {

	@Test
	fun `manga subtypes belong to the same content family`() {
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
				assertTrue(left.isSameContentFamilyAs(right), "$left should accept $right")
			}
		}
	}

	@Test
	fun `novel and video families remain isolated`() {
		assertTrue(ContentType.NOVEL.isSameContentFamilyAs(ContentType.HENTAI_NOVEL))
		assertTrue(ContentType.VIDEO.isSameContentFamilyAs(ContentType.HENTAI_VIDEO))
		assertFalse(ContentType.MANGA.isSameContentFamilyAs(ContentType.NOVEL))
		assertFalse(ContentType.MANGA.isSameContentFamilyAs(ContentType.VIDEO))
		assertFalse(ContentType.NOVEL.isSameContentFamilyAs(ContentType.VIDEO))
	}

	@Test
	fun `other only accepts other and unknown types are rejected`() {
		assertTrue(ContentType.OTHER.isSameContentFamilyAs(ContentType.OTHER))
		assertFalse(ContentType.OTHER.isSameContentFamilyAs(ContentType.MANGA))
		assertFalse(null.isSameContentFamilyAs(ContentType.MANGA))
		assertFalse(ContentType.MANGA.isSameContentFamilyAs(null))
	}
}
