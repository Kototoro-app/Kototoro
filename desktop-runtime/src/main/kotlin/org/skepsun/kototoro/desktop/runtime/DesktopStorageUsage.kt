package org.skepsun.kototoro.desktop.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

/** Where a byte on the Windows data volume belongs; Android's page splits the same numbers by its own caches. */
enum class DesktopStorageCategory { DATABASE, IMAGES, PREFERENCES, LOGS, OTHER, AVAILABLE }

/**
 * Android's storage page, measured on the Windows data root. Sizes are attributed by the root's own children instead of
 * by probing every cache directory, so a new store shows up in [DesktopStorageCategory.OTHER] rather than being missed.
 */
data class DesktopStorageUsage(
    val items: List<Item>,
    /** The volume's full size, which includes data that is not this application's. */
    val volumeBytes: Long,
) {
    data class Item(val category: DesktopStorageCategory, val bytes: Long, val percent: Float)

    val usedBytes: Long get() = items.filter { it.category != DesktopStorageCategory.AVAILABLE }.sumOf { it.bytes }

    val availableBytes: Long get() = find(DesktopStorageCategory.AVAILABLE)?.bytes ?: 0L

    fun find(category: DesktopStorageCategory): Item? = items.firstOrNull { it.category == category }
}

/**
 * Walks the data root once. It only reads metadata, so the page can show figures before anything is changed, and a
 * child that does not exist yet (a fresh install has no logs) is simply absent instead of an error.
 */
fun desktopStorageUsage(paths: DesktopDataPaths): DesktopStorageUsage {
    val root = paths.root
    val used = linkedMapOf<DesktopStorageCategory, Long>()
    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.newDirectoryStream(root).use { children ->
            for (child in children) {
                used.merge(categoryOf(paths, child), sizeOf(child), Long::plus)
            }
        }
    }
    // The volume, not the root, is what Android calls the total: a 400 GB disk with 300 GB free reads like Android.
    val file = root.toFile()
    val owned = used.values.sum()
    val volume = maxOf(file.totalSpace, owned)
    val available = file.usableSpace.coerceIn(0L, volume)
    val items = DesktopStorageCategory.entries.mapNotNull { category ->
        val bytes = if (category == DesktopStorageCategory.AVAILABLE) available else used[category]
        bytes?.takeIf { it > 0L }?.let {
            DesktopStorageUsage.Item(category, it, percent = (it * 100.0 / volume).toFloat())
        }
    }
    return DesktopStorageUsage(items = items, volumeBytes = volume)
}

/** The database keeps its write-ahead log and shared memory beside it, so all three are counted as one store. */
private fun categoryOf(paths: DesktopDataPaths, child: Path): DesktopStorageCategory {
    val name = child.name
    return when {
        name == paths.database.name || name.startsWith("${paths.database.name}-") -> DesktopStorageCategory.DATABASE
        name == paths.images.name -> DesktopStorageCategory.IMAGES
        name == paths.preferences.name -> DesktopStorageCategory.PREFERENCES
        name == paths.logs.name -> DesktopStorageCategory.LOGS
        else -> DesktopStorageCategory.OTHER
    }
}

/** A symlinked child is skipped rather than followed: a link out of the data root is not this volume's usage. */
private fun sizeOf(path: Path): Long = when {
    Files.isSymbolicLink(path) -> 0L
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.walk(path).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { sizeOrZero(it) }.sum()
    }
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> sizeOrZero(path)
    else -> 0L
}

private fun sizeOrZero(path: Path): Long = try {
    Files.size(path)
} catch (_: IOException) {
    0L
}
