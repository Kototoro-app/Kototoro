package org.skepsun.kototoro.favourites.domain.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand resolution of the real projections behind favourites cards
 * (favourites-komikku-alignment plan, section 7.2).
 *
 * The library rows carry a display stub instead of a full [Content] (share links,
 * downloads, the category dialog and the override editor need the stored projection),
 * so those actions resolve the selected entities *after* the user triggers them — in one
 * batched query, never eagerly for the whole library.
 */
@Singleton
class FavouriteContentResolver @Inject constructor(
    private val database: MangaDatabase,
) {

    /**
     * Stored projections of the given display manga ids, in the order they were asked
     * for (selection order is preserved for the share/category dialogs). Ids of broken
     * rows (no display projection) and unknown ids are skipped.
     */
    suspend fun resolveByDisplayMangaIds(displayMangaIds: Collection<Long>): List<Content> =
        withContext(Dispatchers.Default) {
            val ids = displayMangaIds.filterTo(ArrayList(displayMangaIds.size)) { it > 0L }
            if (ids.isEmpty()) {
                return@withContext emptyList()
            }
            val byId = database.getMangaDao().findWithTagsByIds(ids.distinct())
                .associateBy { it.manga.id }
            ids.mapNotNull { byId[it] }.map { it.toContent() }
        }
}
