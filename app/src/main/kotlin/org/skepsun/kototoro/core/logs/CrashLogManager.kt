package org.skepsun.kototoro.core.logs

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages crash log files on disk.
 * Each crash is saved as a separate file in the app's files/crash_logs directory.
 */
object CrashLogManager {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_LOG_FILES = 20
    private const val FILE_PREFIX = "crash_"
    private const val FILE_SUFFIX = ".log"

    fun getLogDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).apply { mkdirs() }
    }

    fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val logDir = getLogDir(context)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logDir, "$FILE_PREFIX$timestamp$FILE_SUFFIX")

            val sw = StringWriter()
            val pw = PrintWriter(sw)

            pw.println("=== Crash Report ===")
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            pw.println("App Version: ${getAppVersion(context)}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Thread: ${thread.name}")
            pw.println()
            pw.println("=== Stack Trace ===")
            throwable.printStackTrace(pw)

            // Include cause chain
            var cause = throwable.cause
            while (cause != null) {
                pw.println()
                pw.println("=== Caused by ===")
                cause.printStackTrace(pw)
                cause = cause.cause
            }

            pw.flush()
            logFile.writeText(sw.toString())

            // Cleanup old logs
            pruneOldLogs(logDir)
        } catch (_: Exception) {
            // Don't crash while logging a crash
        }
    }

    fun getLogFiles(context: Context): List<File> {
        val logDir = getLogDir(context)
        return logDir.listFiles { file ->
            file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun readLog(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    fun clearAll(context: Context) {
        val logDir = getLogDir(context)
        logDir.listFiles()?.forEach { it.delete() }
    }

    fun getLogCount(context: Context): Int {
        return getLogFiles(context).size
    }

    private fun pruneOldLogs(logDir: File) {
        val files = logDir.listFiles { file ->
            file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: return

        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { it.delete() }
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
