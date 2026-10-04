package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentChapter

/** Projects Android parser chapters onto the shared migration mapping payload. */
object ChapterIdMapper {

    fun map(old: List<ContentChapter>, new: List<ContentChapter>): Map<Long, Long> =
        MigrationChapterMapper.map(old.map { it.toMigrationChapter() }, new.map { it.toMigrationChapter() })

    fun idAtIndex(new: List<ContentChapter>, index: Int): Long? =
        MigrationChapterMapper.idAtIndex(new.map { it.toMigrationChapter() }, index)
}

internal fun ContentChapter.toMigrationChapter() = MigrationChapter(
    id = id,
    volume = volume,
    number = number,
    branch = branch,
    uploadDate = uploadDate,
)
