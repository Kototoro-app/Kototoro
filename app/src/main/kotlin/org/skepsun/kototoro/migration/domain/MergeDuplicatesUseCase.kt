package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.migration.data.LibraryRow
import javax.inject.Inject

/**
 * Folds duplicate favourites into the one the user keeps. Categories, notes, stats and
 * tracking move over; reading progress moves only when a duplicate was read further than
 * the kept entry, so the furthest progress always survives. The duplicates leave
 * favourites and history, but their rows stay (local downloads, bookmarks).
 */
class MergeDuplicatesUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val contentDataRepository: ContentDataRepository,
    private val migrateUseCase: MigrateUseCase,
) {

    suspend operator fun invoke(keep: LibraryRow, duplicates: List<LibraryRow>) {
        val target = checkNotNull(contentDataRepository.findContentById(keep.id, withChapters = true)) {
            "Content ${keep.id} not found"
        }
        var furthest = keep
        for (duplicate in duplicates) {
            val source = checkNotNull(contentDataRepository.findContentById(duplicate.id, withChapters = true)) {
                "Content ${duplicate.id} not found"
            }
            val takeProgress = DuplicateGrouper.isFurther(duplicate, furthest)
            if (takeProgress) furthest = duplicate
            val flags = if (takeProgress) {
                MigrationDataFlag.ALL
            } else {
                MigrationDataFlag.ALL - MigrationDataFlag.PROGRESS
            }
            migrateUseCase(source, target, MigrationMode.REPLACE, flags, targetInLibrary = true)
            // The duplicate is gone from the library; do not leave it behind in history.
            database.getHistoryDao().delete(duplicate.id)
        }
    }
}
