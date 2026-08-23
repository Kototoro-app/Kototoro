package org.skepsun.kototoro.extensions.recovery

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.model.UnknownContentSource

class SourceRefreshDiagnosticsTest {

    private val sourceKey = "TSUNDOKU_9001"
    private val pkg = "eu.kanade.tachiyomi.novelextension.en.x"

    @Test
    fun `cloudflare classifies with cloudflare prefix and sanitized url`() {
        val error = CloudFlareBlockedException(
            url = "https://example.com/n/chapter?id=7&token=abc",
            source = null,
        )

        val line = SourceRefreshDiagnostics.classify(sourceKey, "CHAPTER_LIST", error, pkg)

        assertEquals(
            "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST " +
                "[cloudflare] Blocked by CloudFlare url=https://example.com/n/chapter?id=7&token=***",
            line,
        )
    }

    @Test
    fun `interactive action classifies with action-required prefix`() {
        val error = InteractiveActionRequiredException(
            source = UnknownContentSource,
            url = "https://example.com/n/chapter",
        )

        val line = SourceRefreshDiagnostics.classify(sourceKey, "CHAPTER_LIST", error, pkg)

        val prefix = "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST"
        assertTrue(line.startsWith("$prefix [action-required]"), line)
        assertTrue(line.endsWith("url=https://example.com/n/chapter"), line)
    }

    @Test
    fun `io exception classifies with io prefix and retry hint`() {
        val error = SocketTimeoutException("socket timed out")

        val line = SourceRefreshDiagnostics.classify(sourceKey, "CHAPTER_LIST", error, pkg)

        assertEquals(
            "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST " +
                "[io] socket timed out (retryable)",
            line,
        )
    }

    @Test
    fun `generic exception classifies with error prefix and omits empty pkg segment`() {
        val line = SourceRefreshDiagnostics.classify(
            sourceKey,
            "DETAILS",
            IllegalStateException("boom"),
        )

        assertEquals("TSUNDOKU_9001 phase=DETAILS [error] boom", line)
    }

    @Test
    fun `cancellation is not special-cased and falls back to error`() {
        val line = SourceRefreshDiagnostics.classify(
            sourceKey,
            "CHAPTER_LIST",
            CancellationException("cancelled"),
        )

        assertTrue(line.contains("[error] cancelled"))
    }

    @Test
    fun `masks token password api_key and key query values case-insensitively`() {
        val sanitized = SourceRefreshDiagnostics.sanitizeUrl(
            "https://example.com/feed?lang=en&token=a&Password=b&api_key=c&key=d",
        )

        assertEquals(
            "https://example.com/feed?lang=en&token=***&Password=***&api_key=***&key=***",
            sanitized,
        )
    }

    @Test
    fun `strips userinfo from url`() {
        val sanitized = SourceRefreshDiagnostics.sanitizeUrl(
            "https://user:secret@example.com/feed?token=abc",
        )

        assertEquals("https://example.com/feed?token=***", sanitized)
    }

    @Test
    fun `summary formats single line and redacts embedded url`() {
        val line = SourceRefreshDiagnostics.summary(
            sourceKey,
            pkg,
            "CHAPTER_LIST",
            "[io] socket timed out\nurl=https://user:pass@example.com/ch?password=secret&lang=en",
        )

        assertEquals(
            "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST " +
                "[io] socket timed out url=https://example.com/ch?password=***&lang=en",
            line,
        )
    }

    @Test
    fun `summary keeps base url and non-sensitive query parameters`() {
        val line = SourceRefreshDiagnostics.summary(
            sourceKey,
            pkg,
            "CHAPTER_LIST",
            "ok url=https://example.com/feed?lang=en&page=2",
        )

        assertEquals(
            "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST " +
                "ok url=https://example.com/feed?lang=en&page=2",
            line,
        )
    }

    @Test
    fun `summary renders a plain message without a url segment`() {
        val line = SourceRefreshDiagnostics.summary(sourceKey, pkg, "CHAPTER_LIST", "[error] boom")

        assertEquals(
            "TSUNDOKU_9001 pkg=eu.kanade.tachiyomi.novelextension.en.x phase=CHAPTER_LIST [error] boom",
            line,
        )
    }
}
