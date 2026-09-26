package org.skepsun.kototoro.space.ui

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content

class MediaUniverseViewModelTest {

	@Test
	fun `same projection is merged across history and favorites`() {
		val history = content(contentId = 1L)
		val favorite = content(contentId = 1L)

		val result = mergeMediaUniverseItems(listOf(history), listOf(favorite))

		result shouldHaveSize 1
		result.single().content.id shouldBe 1L
		result.single().inHistory shouldBe true
		result.single().inFavorites shouldBe true
	}

	@Test
	fun `distinct projections stay isolated by content id`() {
		val first = content(contentId = 1L)
		val duplicate = content(contentId = 1L)
		val second = content(contentId = 2L)

		val result = mergeMediaUniverseItems(listOf(first), listOf(duplicate, second))

		result shouldHaveSize 2
		result.first().inHistory shouldBe true
		result.first().inFavorites shouldBe true
		result.last().inHistory shouldBe false
		result.last().inFavorites shouldBe true
	}

	private fun content(contentId: Long): Content = Content(
		id = contentId,
		title = "Content $contentId",
		altTitles = emptySet(),
		url = "/$contentId",
		publicUrl = "https://example.invalid/$contentId",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = TestContentSource,
	)
}
