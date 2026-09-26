package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class DetailsOriginProjectionTest {

	@Test
	fun `local content origin keeps the projection seed for initial details loading`() {
		val content = content("MH1234")
		val origin = DetailsOrigin.LocalMangaContent(
			org.skepsun.kototoro.core.model.parcelable.ParcelableContent(content),
		)

		val intent = origin.initialProjectionIntentOrNull()

		assertSame(content, intent?.manga)
		assertEquals(content.id, intent?.mangaId)
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
