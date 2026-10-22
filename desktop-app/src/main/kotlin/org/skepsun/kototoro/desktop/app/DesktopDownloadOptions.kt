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
