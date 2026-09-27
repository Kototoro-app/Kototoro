package org.skepsun.kototoro.backups.domain

import org.skepsun.kototoro.backups.data.BackupRepository

/**
 * Kind of backup detected from its index when restoring. Every kind restores through the
 * same path; the kind picks the default restore mode and, for Kotatsu / older Kototoro
 * backups, limits restore to the library sections: their sources, settings and auth use
 * keys this app does not share.
 */
enum class BackupRestoreFormat(
    val defaultRestoreMode: BackupRepository.RestoreMode,
) {
    /** A backup written by a current Kototoro (semantic schema >= 3). */
    KOTOTORO_CURRENT(BackupRepository.RestoreMode.SNAPSHOT_REPLACE),

    /** A Kotatsu backup or one written by an older Kototoro. */
    KOTATSU_OR_LEGACY_KOTOTORO(BackupRepository.RestoreMode.MERGE),
    ;

    fun supports(section: BackupSection): Boolean {
        return this != KOTATSU_OR_LEGACY_KOTOTORO || section in KOTATSU_COMPATIBLE_SECTIONS
    }

    fun sanitize(sections: Set<BackupSection>): Set<BackupSection> {
        return sections.filterTo(LinkedHashSet(), ::supports)
    }

    companion object {
        val KOTATSU_COMPATIBLE_SECTIONS = listOf(
            BackupSection.INDEX,
            BackupSection.HISTORY,
            BackupSection.CATEGORIES,
            BackupSection.FAVOURITES,
            BackupSection.BOOKMARKS,
            BackupSection.STATS,
        )
    }
}
