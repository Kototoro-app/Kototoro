package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant

/** Android parser-model boundary. Write planning is owned by the shared [MigrationPlanRules]. */
object MigrationPlanner {

    fun plan(
        old: Content,
        new: Content,
        snapshot: MigrationSnapshot,
        mode: MigrationMode,
        flags: Set<MigrationDataFlag>,
        now: Long,
    ): MigrationPlan {
        val history = snapshot.history?.takeIf {
            MigrationDataFlag.PROGRESS in flags && !new.chapters.isNullOrEmpty()
        }
        val oldBranch = if (history != null && !old.chapters.isNullOrEmpty()) {
            old.getPreferredBranch(history.toContentHistory())
        } else {
            null
        }
        val newBranch = if (history != null && (
                old.chapters.isNullOrEmpty() || new.chapters.orEmpty().none { it.branch == oldBranch }
            )
        ) {
            new.getPreferredBranch(null)
        } else {
            null
        }
        return MigrationPlanRules.plan(
            old = old.toMigrationInput(oldBranch),
            new = new.toMigrationInput(newBranch),
            snapshot = snapshot,
            mode = mode,
            flags = flags,
            now = now,
        )
    }

    private fun Content.toMigrationInput(preferredBranch: String?) = MigrationContentInput(
        id = id,
        sourceName = source.name,
        chapters = chapters.orEmpty().map { it.toMigrationChapter() },
        preferredBranch = preferredBranch,
    )

    private fun HistoryEntity.toContentHistory() = ContentHistory(
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        chapterId = chapterId,
        page = page,
        scroll = scroll.toInt(),
        percent = percent,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )
}
