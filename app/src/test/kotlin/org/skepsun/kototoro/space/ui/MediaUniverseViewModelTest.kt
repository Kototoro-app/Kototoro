package org.skepsun.kototoro.space.ui

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkMigrationState

class MediaUniverseViewModelTest {

	@Test
	fun `same entity is merged across history and favorites`() {
		val history = aggregate(entityId = 42L, contentId = 1L)
		val favorite = aggregate(entityId = 42L, contentId = 2L)

		val result = mergeMediaUniverseItems(listOf(history), listOf(favorite))

		result shouldHaveSize 1
		result.single().content.id shouldBe 1L
		result.single().inHistory shouldBe true
		result.single().inFavorites shouldBe true
	}

	@Test
	fun `unbound content is isolated by content id`() {
		val first = aggregate(entityId = null, contentId = 1L)
		val duplicate = aggregate(entityId = null, contentId = 1L)
		val second = aggregate(entityId = null, contentId = 2L)

		val result = mergeMediaUniverseItems(listOf(first), listOf(duplicate, second))

		result shouldHaveSize 2
		result.first().inHistory shouldBe true
		result.first().inFavorites shouldBe true
	}

	private fun aggregate(entityId: Long?, contentId: Long): WorkAggregate {
		val content = Content(
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
		return WorkAggregate(
			identity = WorkIdentity(
				entityId = entityId,
				requestedMangaId = contentId,
				preferredMangaId = contentId,
				localMangaIds = setOf(contentId),
				migrationState = WorkMigrationState.VALID,
			),
			displayProjection = content,
			projections = listOf(content),
		)
	}
}
