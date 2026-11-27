package org.skepsun.kototoro.explore.domain

import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import javax.inject.Inject

/**
 * Which feed items are already in the library: favourited in at least one active category, the same rule
 * as the card's favourite badge, but independent of whether that badge is shown.
 */
interface DiscoveryFeedLibraryMembership {

    suspend fun libraryIdsOf(contentIds: Collection<Long>): Set<Long>

    /** Emits whenever favourites change, so the caller can recheck its ids. */
    fun observeChanges(): Flow<Unit>
}

class FavouritesDiscoveryFeedLibraryMembership @Inject constructor(
    private val favouritesRepository: FavouritesRepository,
) : DiscoveryFeedLibraryMembership {

    override suspend fun libraryIdsOf(contentIds: Collection<Long>): Set<Long> {
        return favouritesRepository.getCategoriesIds(contentIds)
            .filterValues { it.isNotEmpty() }
            .keys
    }

    override fun observeChanges(): Flow<Unit> = favouritesRepository.observeFavouriteBadgeChanges()
}
