package org.skepsun.kototoro.backups.domain

enum class BackupRestoreFormat {
	KOTOTORO_CURRENT,
	KOTATSU_OR_LEGACY_KOTOTORO,
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
