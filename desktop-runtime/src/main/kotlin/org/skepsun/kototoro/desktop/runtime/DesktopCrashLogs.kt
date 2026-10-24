package org.skepsun.kototoro.desktop.runtime

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One crash report on disk, as the list needs it: when it happened, how big it is and what threw. */
data class DesktopCrashLogFile(
    val path: Path,
    val name: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    /** The first line of the report that names an exception, which is what Android's list shows under the time. */
    val summary: String,
)

/**
 * Android's crash logs (`CrashLogManager`) on the Windows side: one text file per uncaught exception under
 * `<data root>/logs/crash_logs`, named `crash_yyyyMMdd_HHmmss.log`, the newest [MAX_FILES] kept.
 *
 * The directory name, the file names and the cap are Android's own, so a report can be carried between the two hosts
 * and the storage page keeps counting the whole `logs` directory as a single slice. The header swaps Android's device
 * lines for the Windows ones (OS, Java, user) and keeps the rest of the layout, including the `=== Caused by ===`
 * sections.
 */
class DesktopCrashLogs(
    val directory: Path,
    private val version: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Writes [error] as a new report and prunes the directory; returns the file, or null when even the write failed.
     *
     * This runs inside the JVM's uncaught-exception handler, so it must not throw: a failure here has to stay a
     * missing log rather than a second crash.
     */
    fun write(thread: Thread, error: Throwable): DesktopCrashLogFile? = try {
        Files.createDirectories(directory)
        val now = clock()
        val base = FILE_PREFIX + fileNameTime.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        // Android names a report by the second, so a second crash inside the same second overwrote the first; here the
        // extra ones take a counter instead, because a report nobody can read is worse than a longer name.
        var target = directory.resolve("$base$FILE_SUFFIX")
        var attempt = 1
        while (Files.exists(target)) {
            target = directory.resolve("${base}_$attempt$FILE_SUFFIX")
            attempt++
        }
        // A crash during a crash, or two crashes in one second, must not interleave into a half-written file.
        val scratch = directory.resolve("${target.fileName}.part")
        Files.writeString(scratch, report(now, thread, error), StandardCharsets.UTF_8)
        Files.move(scratch, target, StandardCopyOption.REPLACE_EXISTING)
        prune()
        target.toLogFile()
    } catch (_: Exception) {
        null
    }

    /** The reports on disk, newest first; an unreadable or missing directory is an empty list, not an error. */
    fun files(): List<DesktopCrashLogFile> = try {
        Files.newDirectoryStream(directory).use { entries ->
            entries.filter { isLogName(it.fileName.toString()) }
                .mapNotNull { it.toLogFile() }
                .sortedWith(compareByDescending<DesktopCrashLogFile> { it.modifiedAt }.thenByDescending { it.name })
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** The report's text, or null when it disappeared or cannot be read. */
    fun read(log: DesktopCrashLogFile): String? = try {
        Files.readString(log.path, StandardCharsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun delete(log: DesktopCrashLogFile): Boolean = try {
        Files.deleteIfExists(log.path)
        true
    } catch (_: Exception) {
        false
    }

    /** Removes every report; returns how many files went away so the page can report it. */
    fun clear(): Int = files().count { delete(it) }

    /** Keeps the newest [MAX_FILES] reports, so a crash loop cannot fill the disk. */
    private fun prune() {
        files().drop(MAX_FILES).forEach { delete(it) }
    }

    /** The body of one report: Android's header, the stack trace, then each cause in its own section. */
    private fun report(now: Long, thread: Thread, error: Throwable): String {
        val text = StringWriter()
        val writer = PrintWriter(text)
        writer.println("=== Crash Report ===")
        writer.println("Time: ${headerTime.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))}")
        writer.println("App Version: ${version.ifBlank { "unknown" }}")
        writer.println("Windows: ${System.getProperty("os.name").orEmpty()} " +
            "${System.getProperty("os.version").orEmpty()} (${System.getProperty("os.arch").orEmpty()})")
        writer.println("Java: ${System.getProperty("java.version").orEmpty()} " +
            "(${System.getProperty("java.vendor").orEmpty()})")
        writer.println("User: ${System.getProperty("user.name").orEmpty()}")
        writer.println("Thread: ${thread.name}")
        writer.println()
        writer.println("=== Stack Trace ===")
        error.printStackTrace(writer)
        var cause = error.cause
        while (cause != null) {
            writer.println()
            writer.println("=== Caused by ===")
            cause.printStackTrace(writer)
            cause = cause.cause
        }
        writer.flush()
        return text.toString()
    }

    private fun Path.toLogFile(): DesktopCrashLogFile? = try {
        val size = Files.size(this)
        DesktopCrashLogFile(
            path = this,
            name = fileName.toString(),
            modifiedAt = Files.getLastModifiedTime(this, LinkOption.NOFOLLOW_LINKS).toMillis(),
            sizeBytes = size,
            summary = summary(this),
        )
    } catch (_: Exception) {
        null
    }

    /**
     * The line Android's list shows: the first stack-trace line that names an exception.
     *
     * Read through the file's first [SUMMARY_BYTES] only, because the list can hold twenty reports and none of them
     * needs to be parsed whole to be listed.
     */
    private fun summary(path: Path): String = try {
        Files.newInputStream(path).use { input ->
            val head = input.readNBytes(SUMMARY_BYTES).toString(StandardCharsets.UTF_8)
            head.lineSequence().map { it.trim() }
                .firstOrNull { it.contains("Exception") || it.contains("Error") }
                ?: head.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: path.fileName.toString()
        }
    } catch (_: Exception) {
        path.fileName.toString()
    }

    companion object {
        /** Android's directory name under `filesDir`; here it sits under the data root's `logs`. */
        const val DIRECTORY_NAME = "crash_logs"

        /** Android's cap on kept reports. */
        const val MAX_FILES = 20

        private const val FILE_PREFIX = "crash_"
        private const val FILE_SUFFIX = ".log"
        private const val SUMMARY_BYTES = 8 * 1024
        private val fileNameTime = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        private val headerTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /** The reports of one data root; [version] is what the report names as the application version. */
        fun of(paths: DesktopDataPaths, version: String = ""): DesktopCrashLogs =
            DesktopCrashLogs(paths.logs.resolve(DIRECTORY_NAME), version)

        private val target = AtomicReference<DesktopCrashLogs?>(null)
        private val chained = AtomicReference<Thread.UncaughtExceptionHandler?>(null)
        private val installed = AtomicBoolean(false)

        private fun isLogName(name: String): Boolean = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
    }

    /**
     * Sends uncaught exceptions from any thread to this directory, then to whatever handler the JVM had before
     * (Android's `CrashLogWriter` chains on the previous handler the same way, so a launcher's own reporting keeps
     * working). Installing twice for another data root only re-points the writer.
     */
    fun install() {
        target.set(this)
        if (!installed.compareAndSet(false, true)) return
        chained.set(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            target.get()?.write(thread, error)
            chained.get()?.uncaughtException(thread, error)
        }
    }
}
