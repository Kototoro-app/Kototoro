package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DesktopCrashLogsTest {
    @TempDir
    lateinit var root: Path

    private fun logs(version: String = "2.3.1", clock: () -> Long = { 1_760_000_000_000L }): DesktopCrashLogs =
        DesktopCrashLogs(root.resolve("logs").resolve("crash_logs"), version, clock)

    @Test
    fun `a report keeps Android's header and stack trace`() {
        val logs = logs()
        val error = IllegalStateException("reader failed").apply {
            initCause(IllegalArgumentException("missing chapter"))
        }

        val file = requireNotNull(logs.write(Thread.currentThread(), error))
        val text = requireNotNull(logs.read(file))

        assertTrue(file.name.startsWith("crash_"), file.name)
        assertTrue(file.name.endsWith(".log"), file.name)
        assertTrue(text.startsWith("=== Crash Report ==="), text)
        assertTrue(text.contains("App Version: 2.3.1"), text)
        assertTrue(text.contains("Thread: ${Thread.currentThread().name}"), text)
        assertTrue(text.contains("${DesktopPlatform.current.displayName}: "), text)
        assertTrue(text.contains("=== Stack Trace ==="), text)
        assertTrue(text.contains("java.lang.IllegalStateException: reader failed"), text)
        assertTrue(text.contains("=== Caused by ==="), text)
        assertTrue(text.contains("java.lang.IllegalArgumentException: missing chapter"), text)
        // Android's own frame-style indentation, so the report reads the same in both hosts.
        assertTrue(text.contains("\tat org.junit"), text)
        assertFalse(Files.exists(logs.directory.resolve("${file.name}.part")))
    }

    @Test
    fun `the list is newest first and names the exception`() {
        val clock = mutableListOf(1_760_000_000_000L, 1_760_000_060_000L, 1_760_000_120_000L)
        val logs = logs(clock = { clock.removeAt(0) })

        logs.write(Thread.currentThread(), IllegalArgumentException("first"))
        logs.write(Thread.currentThread(), NullPointerException("second"))
        logs.write(Thread.currentThread(), OutOfMemoryError("third"))

        val files = logs.files()

        assertEquals(3, files.size)
        assertEquals(
            listOf(
                "java.lang.OutOfMemoryError: third",
                "java.lang.NullPointerException: second",
                "java.lang.IllegalArgumentException: first",
            ),
            files.map { it.summary },
        )
        assertTrue(files.all { it.sizeBytes > 0 })
        assertTrue(files.first().modifiedAt >= files.last().modifiedAt)
    }

    @Test
    fun `only the newest twenty reports survive a crash loop`() {
        var now = 1_760_000_000_000L
        val logs = logs(clock = { now })

        repeat(24) { index ->
            now += 1_000L
            logs.write(Thread.currentThread(), IllegalStateException("crash $index"))
        }

        val files = logs.files()

        assertEquals(DesktopCrashLogs.MAX_FILES, files.size)
        assertTrue(files.first().summary.contains("crash 23"), files.first().summary)
        assertFalse(files.any { it.summary.contains("crash 3") }, files.joinToString { it.summary })
        assertTrue(files.any { it.summary.contains("crash 4") })
    }

    @Test
    fun `deleting and clearing work on a directory that is not there yet`() {
        val logs = logs()

        assertEquals(emptyList<DesktopCrashLogFile>(), logs.files())
        assertEquals(0, logs.clear())
        assertFalse(Files.exists(logs.directory))

        val file = requireNotNull(logs.write(Thread.currentThread(), IllegalStateException("gone")))
        assertTrue(logs.delete(file))
        assertFalse(Files.exists(file.path))
        assertNull(logs.read(file))

        logs.write(Thread.currentThread(), IllegalStateException("one"))
        logs.write(Thread.currentThread(), IllegalStateException("two"))
        assertEquals(2, logs.clear())
        assertEquals(emptyList<DesktopCrashLogFile>(), logs.files())
    }

    @Test
    fun `files that are not reports are left alone`() {
        val logs = logs()
        Files.createDirectories(logs.directory)
        Files.writeString(logs.directory.resolve("notes.txt"), "not a crash")
        Files.writeString(logs.directory.resolve("crash_keep.txt"), "wrong suffix")
        logs.write(Thread.currentThread(), IllegalStateException("real"))

        assertEquals(1, logs.files().size)
        assertEquals(1, logs.clear())
        assertTrue(Files.exists(logs.directory.resolve("notes.txt")))
        assertTrue(Files.exists(logs.directory.resolve("crash_keep.txt")))
    }
}
