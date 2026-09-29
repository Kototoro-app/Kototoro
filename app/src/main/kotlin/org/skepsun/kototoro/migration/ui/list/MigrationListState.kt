package org.skepsun.kototoro.migration.ui.list

import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.parsers.model.Content

enum class MigrationItemStatus { WAITING, SEARCHING, MATCHED, NOT_FOUND, MIGRATING, FAILED }

enum class MigrationFilter { ALL, MATCHED, NOT_FOUND, FEWER_CHAPTERS }

data class MigrationItemState(
    val origin: Content,
    val originChapters: Int,
    val status: MigrationItemStatus = MigrationItemStatus.WAITING,
    val target: Content? = null,
    val targetChapters: Int? = null,
    val candidates: List<MatchCandidate> = emptyList(),
    val sourceErrors: List<String> = emptyList(),
    val failure: String? = null,
) {
    /** Null when either side is unknown; an entry never cached locally reports 0 chapters. */
    val chapterDelta: Int?
        get() = if (target != null && targetChapters != null && originChapters > 0) targetChapters - originChapters else null

    val otherCandidatesCount: Int
        get() = candidates.count { it.content.id != target?.id }
}

sealed interface MigrationDialog {
    data class Confirm(val mode: MigrationMode, val readyCount: Int, val skippedCount: Int) : MigrationDialog
    data class Progress(val done: Int, val total: Int) : MigrationDialog
    data class Result(val succeeded: Int, val failed: Int) : MigrationDialog
    data object Exit : MigrationDialog
}

data class MigrationListState(
    val items: List<MigrationItemState> = emptyList(),
    val filter: MigrationFilter = MigrationFilter.ALL,
    val dialog: MigrationDialog? = null,
    val isLoading: Boolean = true,
) {
    val visibleItems: List<MigrationItemState>
        get() = items.filter { it.matches(filter) }

    val readyCount: Int
        get() = items.count { it.status == MigrationItemStatus.MATCHED }

    val settledCount: Int
        get() = items.count { it.status != MigrationItemStatus.WAITING && it.status != MigrationItemStatus.SEARCHING }

    val isMatching: Boolean
        get() = items.any { it.status == MigrationItemStatus.WAITING || it.status == MigrationItemStatus.SEARCHING }

    fun count(filter: MigrationFilter): Int = items.count { it.matches(filter) }

    fun updateItem(originId: Long, transform: (MigrationItemState) -> MigrationItemState): MigrationListState =
        copy(items = items.map { if (it.origin.id == originId) transform(it) else it })

    fun removeItem(originId: Long): MigrationListState = copy(items = items.filterNot { it.origin.id == originId })

    private fun MigrationItemState.matches(filter: MigrationFilter): Boolean = when (filter) {
        MigrationFilter.ALL -> true
        MigrationFilter.MATCHED -> status == MigrationItemStatus.MATCHED
        MigrationFilter.NOT_FOUND -> status == MigrationItemStatus.NOT_FOUND
        MigrationFilter.FEWER_CHAPTERS -> status == MigrationItemStatus.MATCHED && (chapterDelta ?: 0) < 0
    }
}
