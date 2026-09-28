package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class FindLibraryDuplicatesUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val settings: MigrationSettings,
) {

    /**
     * Favourited entries that look like the same work as [content]. Empty when the check is
     * disabled, when [content] is already favourited, or on any error (never block favouriting).
     */
    suspend operator fun invoke(content: Content): List<LibraryRow> {
        if (!settings.isDuplicateCheckEnabled) return emptyList()
        return runCatchingCancellable {
            if (database.getFavouritesDao().findCategories(content.id).isNotEmpty()) {
                return@runCatchingCancellable emptyList()
            }
            DuplicateMatcher.find(
                id = content.id,
                title = content.title,
                altTitles = content.altTitles,
                family = content.source.getContentType(),
                library = database.getMigrationDao().findLibraryRows(),
            )
        }.getOrDefault(emptyList())
    }
}
