package org.skepsun.kototoro.migration.data

/** One favourited entry with the signals migration, health and duplicate checks need. */
data class LibraryRow(
    val id: Long,
    val title: String,
    val altTitles: String?,
    val source: String,
    val contentType: String?,
    val coverUrl: String,
    val chaptersCount: Int,
    val trackResult: Int?,
    val trackCheckTime: Long?,
    val trackError: String?,
    val historyPercent: Float?,
    val historyChapterNumber: Float?,
    val publicUrl: String? = null,
    val historyChaptersCount: Int? = null,
) {
    /** Best known chapter count: cached chapters, else the count remembered by history. */
    val knownChapters: Int
        get() = maxOf(chaptersCount, historyChaptersCount ?: 0)

    val altTitleList: List<String>
        get() = altTitles?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
