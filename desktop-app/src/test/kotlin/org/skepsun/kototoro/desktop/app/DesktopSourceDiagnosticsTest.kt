package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopSourceDiagnosticsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `failure lookup uses the exact request and logs bounded causes without request or exception secrets`() {
        val diagnostics = DesktopSourceDiagnostics(directory)
        val request = SourceRequest(1, "request-secret", SourceCall.ListContent("MIHON_1", 0,
            filter = SourceFilter(query = "query-secret")))
        val cause = IOException("Authorization cookie-secret https://private.test/token")
        diagnostics.record(request, IllegalStateException("response-body-secret", cause))
        val message = requireNotNull(diagnostics.message(request.requestId))
        assertTrue(message.startsWith("来源网络请求失败（诊断 "))
        assertNull(diagnostics.message("unrelated-request"))
        val log = Files.readString(directory.resolve("logs/source-errors.log"))
        assertTrue(log.contains("operation=ListContent"))
        assertTrue(log.contains(IOException::class.java.name))
        listOf("request-secret", "query-secret", "cookie-secret", "private.test", "response-body-secret")
            .forEach { assertFalse(log.contains(it), it) }
        for (index in 1..20) diagnostics.record(request.copy(requestId = "request-$index"), cause)
        assertNull(diagnostics.message(request.requestId))
        assertNotNull(diagnostics.message("request-20"))
        assertTrue(Files.size(directory.resolve("logs/source-errors.log")) < 128 * 1024)
    }

    @Test
    fun `unwritable log location preserves existing data and still reports a safe runtime category`() {
        val blocked = directory.resolve("logs")
        Files.writeString(blocked, "preserved")
        val diagnostics = DesktopSourceDiagnostics(directory)
        diagnostics.record(SourceRequest(1, "abi", SourceCall.Sources),
            IllegalStateException("private value", NoClassDefFoundError("private class value")))
        val message = requireNotNull(diagnostics.message("abi"))
        assertTrue(message.contains("来源运行时不兼容"))
        assertTrue(message.contains("日志写入失败"))
        assertFalse(message.contains("private"))
        assertEquals("preserved", Files.readString(blocked))
    }
}
