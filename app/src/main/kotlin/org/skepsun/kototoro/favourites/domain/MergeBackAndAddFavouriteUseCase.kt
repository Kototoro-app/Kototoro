package org.skepsun.kototoro.favourites.domain

import dagger.Reusable
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@Reusable
class MergeBackAndAddFavouriteUseCase @Inject constructor(
	private val entityGraphRepository: EntityGraphRepository,
	private val favouritesRepository: FavouritesRepository,
	private val contentDataRepository: ContentDataRepository,
) {

	suspend operator fun invoke(
		categoryId: Long,
		content: Content,
		targetEntityId: Long,
	): Boolean {
		val storedContent = contentDataRepository.storeContentAndReturn(content, replaceExisting = false)
		if (!entityGraphRepository.mergeDetachedProjectionBack(storedContent.id, targetEntityId)) {
			return false
		}
		favouritesRepository.addToCategory(categoryId, listOf(storedContent))
		return true
	}
}
