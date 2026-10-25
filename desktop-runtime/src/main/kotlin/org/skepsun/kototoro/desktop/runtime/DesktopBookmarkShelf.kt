package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.isNsfw

/**
 * The sections Android's bookmarks page draws (`AllBookmarksViewModel` groups the DAO's rows by work and drops the
 * works the adult filter hides). Android orders those sections by the shelf's own sort options; Windows has no sort
 * control there, so the newest saved position comes first, which is the order a bookmark shelf is looked at.
 *
 * [hideNsfw] is Android's `bookmarks_exclude_nsfw` (`KEYS.BOOKMARKS_EXCLUDE_NSFW`); the work's own flag decides, so an
 * adult work never shows while favourites and history stay untouched, exactly like the two Android shelves.
 */
fun desktopBookmarkShelf(groups: List<DesktopBookmarkGroup>, hideNsfw: Boolean): List<DesktopBookmarkGroup> =
    groups.asSequence()
        .filter { it.bookmarks.isNotEmpty() }
        .filterNot { hideNsfw && it.content.isNsfw }
        .sortedWith(
            compareByDescending<DesktopBookmarkGroup> { group -> group.bookmarks.maxOf { it.createdAt } }
                .thenBy { it.content.title },
        )
        .toList()
