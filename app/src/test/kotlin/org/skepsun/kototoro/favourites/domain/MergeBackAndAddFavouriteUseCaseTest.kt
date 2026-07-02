package org.skepsun.kototoro.favourites.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class MergeBackAndAddFavouriteUseCaseTest {

	private val entityGraphRepository = mockk<EntityGraphRepository>()
	private val favouritesRepository = mockk<FavouritesRepository>(relaxed = true)
	private val contentDataRepository = mockk<ContentDataRepository>()
	private val useCase = MergeBackAndAddFavouriteUseCase(
		entityGraphRepository = entityGraphRepository,
		favouritesRepository = favouritesRepository,
		contentDataRepository = contentDataRepository,
	)

	@Test
	fun `invoke merges projection back before adding favourite`() = runTest {
		val content = content(10L)
		val stored = content(11L)
		coEvery { contentDataRepository.storeContentAndReturn(content, replaceExisting = false) } returns stored
		coEvery { entityGraphRepository.mergeDetachedProjectionBack(11L, 2L) } returns true

		assertTrue(useCase(categoryId = 5L, content = content, targetEntityId = 2L))

		coVerify(ordering = io.mockk.Ordering.SEQUENCE) {
			contentDataRepository.storeContentAndReturn(content, replaceExisting = false)
			entityGraphRepository.mergeDetachedProjectionBack(11L, 2L)
			favouritesRepository.addToCategory(5L, listOf(stored))
		}
	}

	@Test
	fun `invoke does not add favourite when merge back fails`() = runTest {
		val content = content(10L)
		coEvery { contentDataRepository.storeContentAndReturn(content, replaceExisting = false) } returns content
		coEvery { entityGraphRepository.mergeDetachedProjectionBack(10L, 2L) } returns false

		assertFalse(useCase(categoryId = 5L, content = content, targetEntityId = 2L))

		coVerify(exactly = 0) {
			favouritesRepository.addToCategory(any(), any())
		}
	}

	private fun content(id: Long): Content {
		return Content(
			id = id,
			title = "Work $id",
			altTitles = emptySet(),
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = object : ContentSource {
				override val name: String = "source"
				override val locale: String = ""
				override val contentType: ContentType = ContentType.MANGA
			},
		)
	}
}
