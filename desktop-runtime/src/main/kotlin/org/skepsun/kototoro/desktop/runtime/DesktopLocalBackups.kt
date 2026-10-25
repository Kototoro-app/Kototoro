package org.skepsun.kototoro.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android's `BackupUtils` name: `<app>_yyyyMMdd-HHmm.bk.zip`, the format its restore page lists. */
private const val NAME_PATTERN = "yyyyMMdd-HHmm"

/** Lower case because Android lowercases `app_name`; the file name must match the ones copied from a phone. */
private const val NAME_PREFIX = "kototoro"

/** Android's suffix; a file without it is something the user saved by hand and is never trimmed. */
const val DESKTOP_BACKUP_SUFFIX = ".bk.zip"

/** The staging suffix of a copy in progress: it does not parse as a backup, so it is never listed or trimmed. */
private const val PART_SUFFIX = ".part"

/**
 * The file name of a periodical backup taken at [now], Android's `BackupUtils.generateFileName`.
 *
 * Windows keeps the same shape as Android so the backups directory of either platform can be read on the other one and
 * a user recognises the file they picked on their phone.
 */
fun desktopBackupFileName(now: Long): String = buildString {
    append(NAME_PREFIX)
    append('_')
    append(SimpleDateFormat(NAME_PATTERN, Locale.US).format(Date(now)))
    append(DESKTOP_BACKUP_SUFFIX)
}

/**
 * The instant in [name], or null when the name is not one of ours — a manual export, a staging file or a directory.
 * Retention uses this, so a file the user saved into the directory is never mistaken for an old automatic backup.
 */
fun desktopBackupTimestamp(name: String): Long? {
    if (!name.endsWith(DESKTOP_BACKUP_SUFFIX)) return null
    val stamp = name.removeSuffix(DESKTOP_BACKUP_SUFFIX).substringAfterLast('_')
    return try {
        SimpleDateFormat(NAME_PATTERN, Locale.US).parse(stamp)?.time
    } catch (_: ParseException) {
        null
    }
}

/**
 * The directory the periodical copies belong in: [chosen] when the user picked one and it is still a writable
 * directory, [fallback] otherwise.
 *
 * Android's `periodicalBackupDirectory` is a tree URI and behaves the same way: a tree that was never resolved, or that
 * the user revoked, makes the backup service fall back to the app's own backup directory instead of failing every
 * backup. A blank [chosen] is the "not configured" case and uses [fallback] directly, so the default is the data root's
 * `backups` folder without anyone having to spell it out.
 */
fun desktopBackupDirectory(chosen: String, fallback: Path): Path {
    if (chosen.isBlank()) return fallback
    val path = try {
        Path.of(chosen)
    } catch (_: Exception) {
        // InvalidPathException and friends: a stored path the JVM cannot even name is the unset case, not a crash.
        return fallback
    }
    return if (Files.isDirectory(path) && Files.isWritable(path)) path else fallback
}

/**
 * Android's local periodical backups on Windows: the library export written to the data root's `backups` directory
 * before the WebDAV upload, so a server that refuses the file still leaves a restorable copy on this machine.
 *
 * Only files this class wrote ([desktopBackupFileName]) take part in retention and listing: a backup the user saved
 * into the same directory by hand has a different name and is left alone, which is Android's rule as well — it lists
 * the directory through `parseBackupDateTime` and skips what it cannot parse.
 */
class DesktopLocalBackups(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    /** The automatic copies, newest first. */
    fun list(): List<Path> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(directory).use { entries ->
            entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && desktopBackupTimestamp(it.fileName.toString()) != null }
                .sortedWith(compareByDescending<Path> { desktopBackupTimestamp(it.fileName.toString()) }.thenBy { it.fileName.toString() })
        }
    }

    /**
     * Copies [source] into the directory under [desktopBackupFileName] for [now] and returns the new file. Two backups
     * inside one minute get `-2`, `-3`, … instead of overwriting the earlier one.
     *
     * The bytes land in a `.part` sibling first and are then moved into place in one step: a crash in the middle of the
     * copy must not leave a truncated file that the restore page would offer as a backup.
     */
    fun keep(source: Path, now: Long = clock()): Path {
        Files.createDirectories(directory)
        val target = unique(directory, desktopBackupFileName(now))
        val part = target.resolveSibling(target.fileName.toString() + PART_SUFFIX)
        try {
            Files.copy(source, part, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(part)
        }
        return target
    }

    /**
     * Deletes everything past the newest [count] copies and returns the removed file names. A count of zero or less
     * keeps everything (Android's `Int.MAX_VALUE` when "delete old backups" is off), so a stray `0` cannot wipe a
     * directory.
     */
    fun trim(count: Int): List<String> {
        if (count <= 0) return emptyList()
        val kept = list()
        if (kept.size <= count) return emptyList()
        val removed = mutableListOf<String>()
        for (file in kept.drop(count)) {
            if (Files.deleteIfExists(file)) removed += file.fileName.toString()
        }
        return removed
    }

    private fun unique(parent: Path, name: String): Path {
        val first = parent.resolve(name)
        if (!Files.exists(first, LinkOption.NOFOLLOW_LINKS)) return first
        val base = name.removeSuffix(DESKTOP_BACKUP_SUFFIX)
        var index = 2
        while (true) {
            val candidate = parent.resolve("$base-$index$DESKTOP_BACKUP_SUFFIX")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
            index++
        }
    }
}
