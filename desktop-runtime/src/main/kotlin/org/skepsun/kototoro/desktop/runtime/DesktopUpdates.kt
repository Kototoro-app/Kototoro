package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.tracker.domain.feed.FeedUpdateRow

/**
 * One row of Android's updates page (`UpdatesViewModel` / `UpdateGroupRow`) after the Windows projection: the works the
 * tracker found new chapters for, folded per work.
 *
 * Android groups by entity, which the shared database no longer carries (`Migration83To84` removed the entity graph), so
 * the projection is the work the track belongs to: `displayMangaId ?: mangaId`. A work tracked from two sources is one
 * row whose counts add up, and [ownerIds] keeps every track so "mark read" can clear them all at once.
 *
 * [title], [coverUrl], [sourceName] and [isNsfw] come from the freshest track of the work — the row that was checked
 * last says what the work looks like now.
 */
data class DesktopUpdateGroup(
    /** The work's content id: what the card opens. */
    val key: Long,
    /** The track ids behind this row (`FeedUpdateRow.ownerId`), the unit "mark read" writes. */
    val ownerIds: List<Long>,
    val title: String,
    val coverUrl: String?,
    val sourceName: String,
    val newChapters: Int,
    val lastChapterDate: Long?,
    val lastCheckTime: Long,
    val isPinned: Boolean,
    val isNsfw: Boolean,
)

/**
 * The page's rows, built from the feed snapshot's tracked works.
 *
 * [hideNsfw] is Android's `sources.nsfw.updates` target that already hides the same works from the feed's carousel, and
 * [acceptsSource] is the page's source/content-type filter — the caller projects a row's source through its content,
 * because only it holds the content map.
 *
 * Ordering follows Android's `UPDATES_ORDER` (newest last chapter first) with the feed's pinned works ahead of it: a
 * pinned work stays on top even when another work has a fresher chapter, which is what pinning means on both shelves.
 */
fun desktopUpdateGroups(
    rows: Iterable<FeedUpdateRow>,
    hideNsfw: Boolean = false,
    acceptsSource: (FeedUpdateRow) -> Boolean = { true },
): List<DesktopUpdateGroup> = rows.asSequence()
    .filter(acceptsSource)
    .filterNot { hideNsfw && it.isNsfw }
    .groupBy { it.displayMangaId ?: it.mangaId }
    .map { (key, tracks) ->
        val representative = tracks.maxWith(
            compareBy<FeedUpdateRow> { it.lastCheckTime }.thenBy { it.ownerId },
        )
        DesktopUpdateGroup(
            key = key,
            ownerIds = tracks.sortedBy { it.ownerId }.map { it.ownerId },
            title = representative.title,
            coverUrl = representative.coverUrl,
            sourceName = representative.sourceName,
            // Android sums the entity's tracks, and a track without a readable counter counts as one chapter.
            newChapters = tracks.sumOf { it.newChapters.coerceAtLeast(0) },
            // A work whose source never reported a chapter date keeps `null`: Android shows "updated long ago" for it
            // and sorts it last instead of pretending the check time was a publication date.
            lastChapterDate = tracks.mapNotNull { it.lastChapterDate }.maxOrNull(),
            lastCheckTime = representative.lastCheckTime,
            isPinned = tracks.any { it.isPinned },
            isNsfw = representative.isNsfw,
        )
    }
    .sortedWith(
        compareByDescending<DesktopUpdateGroup> { it.isPinned }
            .thenByDescending { it.lastChapterDate ?: 0L }
            .thenByDescending { it.lastCheckTime }
            .thenBy { it.title },
    )
    .toList()

/** The header card's sentence: how many works wait and how many chapters they carry. */
data class DesktopUpdatesSummary(val works: Int, val newChapters: Int)

fun desktopUpdatesSummary(groups: List<DesktopUpdateGroup>): DesktopUpdatesSummary = DesktopUpdatesSummary(
    works = groups.size,
    newChapters = groups.sumOf { it.newChapters },
)
