package org.skepsun.kototoro.core.diagnostics

/**
 * The one line a crash-log list shows under a report's timestamp: the first line that names an exception or an error,
 * because that is the part a reader identifies a report by.
 *
 * Android reads the whole file and the desktop reads only its head (a list of twenty reports needs no more than the
 * first block), so the rule itself lives here rather than in either reader: a report whose trace never names an
 * exception falls back to its first non-blank line, and a report that is empty or unreadable falls back to [fallback]
 * (the caller's file name).
 */
fun crashLogSummaryLine(text: String, fallback: String): String {
    val lines = text.lineSequence().map { it.trim() }
    return lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
        ?: lines.firstOrNull { it.isNotEmpty() }
        ?: fallback
}
