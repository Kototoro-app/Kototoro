package org.skepsun.kototoro.core.db.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class MangaContentTypeSnapshotTest {

	@Test
	fun `content mapping persists resolved source type`() {
		assertEquals(ContentType.NOVEL.name, content(LocalNovelSource).toEntity().contentType)
	}

	@Test
	fun `content mapping leaves unresolved source type null`() {
		assertNull(content(UnknownContentSource).toEntity().contentType)
	}

	@Test
	fun `known external source prefix resolves without installed extension`() {
		assertEquals(
			ContentType.MANGA,
			org.skepsun.kototoro.core.model.ContentSource("MIHON_123").resolvedContentTypeForSnapshot(),
		)
		assertEquals(
			ContentType.VIDEO,
			org.skepsun.kototoro.core.model.ContentSource("ANIYOMI_456").resolvedContentTypeForSnapshot(),
		)
		assertNull(
			org.skepsun.kototoro.core.model.ContentSource("UNAVAILABLE_SOURCE").resolvedContentTypeForSnapshot(),
		)
	}

	private fun content(source: ContentSource) = Content(
		id = 1L,
		title = "Title",
		altTitles = emptySet(),
		url = "/work",
		publicUrl = "https://example.test/work",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)
}
