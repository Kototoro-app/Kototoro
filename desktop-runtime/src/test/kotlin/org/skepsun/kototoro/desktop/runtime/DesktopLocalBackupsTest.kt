package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's local periodical backup directory: the name must be the one a phone writes (`kototoro_yyyyMMdd-HHmm.bk.zip`),
 * retention must only ever touch those files, and two backups inside the same minute must not overwrite each other.
 */
class DesktopLocalBackupsTest {
    @TempDir lateinit var root: Path

    private val minute = 60_000L

    /** Any instant; the tests never assert a literal date, only what the name and the parser make of it. */
    private val start = 1_791_000_000_000L

    private val directory: Path get() = root.resolve("backups")

    private fun backups() = DesktopLocalBackups(directory)

    /** A staged export file, i.e. what the WebDAV upload would otherwise send. */
    private fun staged(bytes: String): Path = Files.writeString(root.resolve("staged-$bytes.zip"), bytes)

    private fun nameAt(minutesAfterStart: Long) = desktopBackupFileName(start + minutesAfterStart * minute)

    private fun names(files: List<Path>) = files.map { it.fileName.toString() }

    @Test
    fun `the file name is Android's and the timestamp reads back`() {
        val name = nameAt(0)

        assertTrue(Regex("""kototoro_\d{8}-\d{4}\.bk\.zip""").matches(name), name)
        assertEquals(start, desktopBackupTimestamp(name))
        assertEquals(start + 90 * minute, desktopBackupTimestamp(nameAt(90)))
        assertNull(desktopBackupTimestamp("kototoro-library-1791000000000.zip"))
        assertNull(desktopBackupTimestamp("backups"))
    }

    @Test
    fun `keeping a copy writes Android's name next to the library it exported`() {
        val backups = backups()
        val kept = backups.keep(staged("first"), start)

        assertEquals(listOf(nameAt(0)), names(backups.list()))
        assertEquals("first", Files.readString(kept))

        // A second backup inside the same minute gets its own file instead of replacing the first one.
        val second = backups.keep(staged("second"), start)

        assertNotEquals(kept, second)
        assertEquals(2, backups.list().size)
        assertEquals("first", Files.readString(kept))
        assertEquals("second", Files.readString(second))
    }

    @Test
    fun `list answers newest first`() {
        val backups = backups()
        backups.keep(staged("older"), start)
        backups.keep(staged("newest"), start + 120 * minute)
        backups.keep(staged("middle"), start + 60 * minute)

        assertEquals(listOf(nameAt(120), nameAt(60), nameAt(0)), names(backups.list()))
    }

    @Test
    fun `retention keeps the newest copies and reports what it removed`() {
        val backups = backups()
        (0L until 4L).map { backups.keep(staged("copy$it"), start + it * minute) }

        // Four copies one minute apart; removal walks the newest-first listing, so the two oldest go in that order.
        assertEquals(listOf(nameAt(1), nameAt(0)), backups.trim(2))
        assertEquals(listOf(nameAt(3), nameAt(2)), names(backups.list()))
        assertEquals(emptyList<String>(), backups.trim(2))
    }

    @Test
    fun `a hand-saved export in the same directory is ignored and never deleted`() {
        val backups = backups()
        val automatic = backups.keep(staged("automatic"), start)
        val manual = directory.resolve("kototoro-library-1791000000000.zip")
        Files.writeString(manual, "manual")

        assertEquals(listOf(automatic), backups.list())
        assertEquals(emptyList<String>(), backups.trim(1))
        assertTrue(Files.exists(manual))
    }

    @Test
    fun `a count of zero keeps everything`() {
        val backups = backups()
        (0L until 3L).map { backups.keep(staged("copy$it"), start + it * minute) }

        assertEquals(emptyList<String>(), backups.trim(0))
        assertEquals(3, backups.list().size)
    }

    @Test
    fun `a copy in progress is neither listed nor trimmed`() {
        val backups = backups()
        Files.createDirectories(directory)
        // A crashed copy leaves its staging file behind; it must not be offered as a backup or take a slot in retention.
        val part = directory.resolve(nameAt(5) + ".part")
        Files.writeString(part, "truncated")

        assertTrue(backups.list().isEmpty())
        assertEquals(emptyList<String>(), backups.trim(1))
        assertTrue(Files.exists(part))

        // A real copy publishes itself in one move and leaves nothing behind.
        backups.keep(staged("done"), start)
        assertEquals(listOf(nameAt(0)), names(backups.list()))
        assertEquals(listOf(part.fileName.toString()), Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".part") }.map { it.fileName.toString() }.toList()
        })
    }

    @Test
    fun `the chosen output directory wins while it is usable`() {
        val chosen = Files.createDirectories(root.resolve("chosen"))

        assertEquals(chosen, desktopBackupDirectory(chosen.toString(), directory))
    }

    @Test
    fun `an unset, missing or unreadable output directory falls back to the data root`() {
        // Android falls back to the app's own directory when the SAF tree is gone, so a revoked folder cannot stop
        // every backup; the same rule here, because the alternative is a backup that silently never happens.
        assertEquals(directory, desktopBackupDirectory("", directory))
        assertEquals(directory, desktopBackupDirectory("   ", directory))
        assertEquals(directory, desktopBackupDirectory(root.resolve("gone").toString(), directory))

        // A plain file is not a directory either, and neither is a path the JVM refuses to name.
        val file = Files.writeString(root.resolve("not-a-directory"), "x")
        assertEquals(directory, desktopBackupDirectory(file.toString(), directory))
        assertEquals(directory, desktopBackupDirectory("bad\u0000path", directory))
    }
}
