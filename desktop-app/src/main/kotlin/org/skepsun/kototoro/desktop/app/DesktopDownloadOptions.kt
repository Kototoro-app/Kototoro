package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceChapter

/** The scopes Android's batch-download dialog offers, without its number picker. */
internal enum class DesktopDownloadScope { WHOLE, UNREAD, BRANCH, FIRST }

/** One entry of the details download menu: a scope plus the branch or chapter limit it needs. */
internal data class DesktopDownloadOption(
    val scope: DesktopDownloadScope,
    val branch: String? = null,
    val count: Int = 0,
)

/** Chapter counts Android offers in its "first N" picker. */
internal val DesktopDownloadMenuOptions = listOf(10, 25, 50)

internal fun desktopDownloadMenuTag(option: DesktopDownloadOption): String =
    "details-download:${option.scope}:${option.branch ?: ""}:${option.count}"

internal fun desktopDownloadMenuLabel(option: DesktopDownloadOption, count: Int, branch: String? = null): String =
    when (option.scope) {
        DesktopDownloadScope.WHOLE -> desktopText("details.downloadWhole", count)
        DesktopDownloadScope.UNREAD -> desktopText("details.downloadUnread", count)
        DesktopDownloadScope.BRANCH -> desktopText(
            "details.downloadBranch",
            branch ?: desktopText("details.chapterBranchDefault"),
            count,
        )
        DesktopDownloadScope.FIRST -> desktopText("details.downloadFirst", option.count, count)
    }

/**
 * Chapters a batch scope still needs, in the caller's reading order.
 *
 * `chapters` must already be sorted ascending, so "unread" is everything after the current chapter
 * instead of a numeric comparison that would break for sources with repeated or missing numbers.
 * Chapters that already have a complete download are dropped: enqueueing them again would rewrite
 * manifests that offline reading depends on.
 */
internal fun desktopDownloadTargets(
    chapters: List<SourceChapter>,
    current: SourceChapter?,
    downloaded: List<SourceChapter>,
    option: DesktopDownloadOption,
): List<SourceChapter> {
    val pending = chapters.indices.filter { index ->
        downloaded.none { DesktopDownloadStore.sameChapter(it, chapters[index]) }
    }
    return when (option.scope) {
        DesktopDownloadScope.WHOLE -> pending.map(chapters::get)
        DesktopDownloadScope.BRANCH -> pending.map(chapters::get).filter { it.branch == option.branch }
        DesktopDownloadScope.FIRST -> pending.take(option.count.coerceAtLeast(0)).map(chapters::get)
        DesktopDownloadScope.UNREAD -> {
            val start = current?.let { anchor ->
                chapters.indexOfFirst { DesktopDownloadStore.sameChapter(anchor, it) }
            } ?: -1
            pending.filter { it > start }.map(chapters::get)
        }
    }
}

/**
 * One shelf work flattened for the batch scopes: its stored chapters in reading order, the chapter it was last
 * read at and the chapters already saved. Kept free of the database so the batch rules can be tested without a
 * library.
 */
internal data class DesktopShelfDownloadWork(
    val contentId: Long,
    val title: String,
    val chapters: List<SourceChapter>,
    val currentChapterId: Long?,
    val downloaded: List<SourceChapter>,
)

/** The chapters a batch scope queues for one work, in that work's own reading order. */
internal data class DesktopShelfDownload(
    val work: DesktopShelfDownloadWork,
    val chapters: List<SourceChapter>,
)

/**
 * Android's batch SAVE per work: every selected work keeps its own progress and its own saved chapters, so the
 * same scope means "everything I have not read yet" for each of them instead of one shared index. Works with
 * nothing left to queue disappear, which is why an empty result means the batch has nothing to do at all.
 *
 * Unlike the details menu, which scopes to the branch the chapter list is filtered to, the batch keeps every
 * branch in the caller's order: a shelf selection names works, not one translation of them.
 */
internal fun desktopShelfDownloadTargets(
    works: List<DesktopShelfDownloadWork>,
    option: DesktopDownloadOption,
): List<DesktopShelfDownload> = works.mapNotNull { work ->
    val current = work.currentChapterId?.let { id -> work.chapters.firstOrNull { it.id == id } }
    val targets = desktopDownloadTargets(work.chapters, current, work.downloaded, option)
    targets.takeIf { it.isNotEmpty() }?.let { DesktopShelfDownload(work, it) }
}

/** The scopes a shelf batch offers: the details menu's, without the per-branch entry no shared label could name. */
internal val DesktopShelfDownloadOptions: List<DesktopDownloadOption> = buildList {
    add(DesktopDownloadOption(DesktopDownloadScope.WHOLE))
    add(DesktopDownloadOption(DesktopDownloadScope.UNREAD))
    DesktopDownloadMenuOptions.forEach { add(DesktopDownloadOption(DesktopDownloadScope.FIRST, count = it)) }
}

internal fun desktopLibraryDownloadTag(option: DesktopDownloadOption): String =
    "library-download:${option.scope}:${option.count}"

internal fun desktopLibraryDownloadLabel(option: DesktopDownloadOption): String = when (option.scope) {
    DesktopDownloadScope.WHOLE -> desktopText("library.downloadWhole")
    DesktopDownloadScope.UNREAD -> desktopText("library.downloadUnread")
    DesktopDownloadScope.FIRST -> desktopText("library.downloadFirst", option.count)
    // Branches are per work, so the shelf never offers that scope; naming the broad one keeps the label total.
    DesktopDownloadScope.BRANCH -> desktopText("library.downloadWhole")
}
