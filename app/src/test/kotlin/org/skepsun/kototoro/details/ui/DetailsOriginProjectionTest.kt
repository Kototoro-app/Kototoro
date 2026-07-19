package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class DetailsOriginProjectionTest {

	@Test
	fun `entity graph origin keeps its requested initial projection`() {
		val origin = DetailsOrigin.EntityGraph(
			entityId = 1612L,
			initialProjectionLocalMangaId = 4515734976139131316L,
		)

		assertEquals(4515734976139131316L, origin.initialProjectionLocalMangaIdOrNull())
	}

	@Test
	fun `synthetic entity graph content is identifiable before local projection load`() {
		assertTrue(content("Entity Graph").isSyntheticEntityGraphContent())
		assertFalse(content("MIHON_4709139914729853090").isSyntheticEntityGraphContent())
	}

	private fun content(sourceName: String) = Content(
		id = 1L,
		title = "Title",
		altTitles = emptySet(),
		url = "",
		publicUrl = "",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = object : ContentSource {
			override val name = sourceName
			override val locale = ""
			override val contentType = ContentType.MANGA
		},
	)
}
