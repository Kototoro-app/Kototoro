package org.skepsun.kototoro.core.ui.preview

/** Android's primary-branch first/latest preview policy, independent of platform parser models. */
data class TabletPreviewChapterGroups<T>(
    val earliest: List<T>,
    val latest: List<T>,
    val firstChapter: T?,
    val isSingleGroup: Boolean,
)

fun <T> resolveTabletPreviewChapters(
    allChapters: List<T>,
    branch: (T) -> String?,
    number: (T) -> Float,
    uploadDate: (T) -> Long,
): TabletPreviewChapterGroups<T> {
    if (allChapters.isEmpty()) {
        return TabletPreviewChapterGroups(emptyList(), emptyList(), null, true)
    }

    val primaryBranch = branch(allChapters.first())
    val branchChapters = if (primaryBranch != null) {
        val filtered = allChapters.filter { branch(it) == primaryBranch }
        if (filtered.isNotEmpty()) filtered else allChapters
    } else {
        allChapters
    }

    if (branchChapters.size <= 5) {
        return TabletPreviewChapterGroups(
            earliest = branchChapters,
            latest = emptyList(),
            firstChapter = branchChapters.firstOrNull(),
            isSingleGroup = true,
        )
    }

    val first = branchChapters.first()
    val last = branchChapters.last()

    val isAscending = when {
        number(first) > 0f && number(last) > 0f && number(first) != number(last) -> {
            number(first) < number(last)
        }
        uploadDate(first) > 0L && uploadDate(last) > 0L && uploadDate(first) != uploadDate(last) -> {
            uploadDate(first) < uploadDate(last)
        }
        else -> true
    }

    val (earliestList, latestList) = if (isAscending) {
        val earliest = branchChapters.take(3)
        val latest = branchChapters.takeLast(3).reversed()
        earliest to latest
    } else {
        val latest = branchChapters.take(3)
        val earliest = branchChapters.takeLast(3).reversed()
        earliest to latest
    }

    return TabletPreviewChapterGroups(
        earliest = earliestList,
        latest = latestList,
        firstChapter = earliestList.firstOrNull(),
        isSingleGroup = false,
    )
}
