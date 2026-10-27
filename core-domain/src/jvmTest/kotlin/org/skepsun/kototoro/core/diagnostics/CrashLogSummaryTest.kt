package org.skepsun.kototoro.core.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrashLogSummaryTest {

    @Test
    fun `names the exception line of a report header`() {
        val text = """
            === Crash Report ===
            Time: 2026-10-27 12:00:00.000
            App Version: 1.2.3

            === Stack Trace ===
            java.lang.NullPointerException: Attempt to invoke virtual method
            	at org.example.Test.run(Test.kt:42)
        """.trimIndent()
        assertEquals(
            "java.lang.NullPointerException: Attempt to invoke virtual method",
            crashLogSummaryLine(text, "crash_20261027_120000.log"),
        )
    }

    @Test
    fun `reports an error line as readily as an exception line`() {
        assertEquals("IllegalStateError in worker", crashLogSummaryLine("Boot ok\nIllegalStateError in worker\n", "f"))
        assertEquals("java.lang.OutOfMemoryError", crashLogSummaryLine("header\n\njava.lang.OutOfMemoryError", "f"))
    }

    @Test
    fun `falls back to the first non-blank line when nothing names an exception`() {
        val text = "\n\n   === Crash Report ===  \nTime: 2026-10-27\n"
        assertEquals("=== Crash Report ===", crashLogSummaryLine(text, "crash_1.log"))
    }

    @Test
    fun `falls back to the caller's file name for an empty report`() {
        assertEquals("crash_2.log", crashLogSummaryLine("", "crash_2.log"))
        assertEquals("crash_2.log", crashLogSummaryLine("  \n\t\n", "crash_2.log"))
    }
}
