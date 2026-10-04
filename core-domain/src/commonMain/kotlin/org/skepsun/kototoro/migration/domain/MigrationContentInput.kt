package org.skepsun.kototoro.migration.domain

/** Narrow chapter payload for migration planning; external parser model ABI stays on the platform. */
data class MigrationChapter(
    val id: Long,
    val volume: Int,
    val number: Float,
    val branch: String?,
    val uploadDate: Long,
)

/** [preferredBranch] supplies the history fallback branch from platform policy; null can be a real branch. */
data class MigrationContentInput(
    val id: Long,
    val sourceName: String,
    val chapters: List<MigrationChapter>,
    val preferredBranch: String?,
)
