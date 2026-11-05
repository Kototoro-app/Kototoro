package org.skepsun.kototoro.core.jsonsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityValidatorBoundsTest {

    @Test
    fun `validateUrl rejects local IP literals in every notation`() {
        val local = listOf(
            "http://localhost:8080", "http://127.0.0.1", "http://0.0.0.0", "http://192.168.1.1", "http://10.0.0.1",
            "http://172.16.0.1", "http://[::1]/", "http://[::]/", "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:192.168.1.1]/", "http://[fe80::1]/", "http://[fd12:3456::1]/",
            "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/", "http://2130706433/",
            "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/", "http://10.1/", "http://foo.localhost/",
            "http://localhost./",
        )
        for (url in local) {
            assertFalse(SecurityValidator.validateUrl(url).isValid, url)
        }
    }

    @Test
    fun `validateUrl accepts public addresses and hex-looking hostnames`() {
        val public = listOf(
            "http://example.com", "http://8.8.8.8/", "http://172.32.0.1", "http://[2001:4860:4860::8888]/",
            "http://100.128.0.1/", "https://cafe.be/", "https://dead.beef.example/", "https://123.example.com/",
        )
        for (url in public) {
            assertTrue(SecurityValidator.validateUrl(url).isValid, url)
        }
    }

    @Test
    fun `findBounded gives up on catastrophic backtracking`() {
        val input = "a".repeat(64) + "!"
        val started = System.nanoTime()
        val match = SecurityValidator.findBounded(Regex("^(a|aa)+\\1$"), input, timeoutMs = 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(match)
        assertTrue(elapsedMs < 5_000, "took $elapsedMs ms")
    }

    @Test
    fun `findBounded returns ordinary matches with groups`() {
        val match = SecurityValidator.findBounded(Regex("<title>(.*?)</title>"), "<title>Kototoro</title>")
        assertEquals("Kototoro", match?.groupValues?.get(1))
    }

    @Test
    fun `replaceBounded gives up on catastrophic backtracking`() {
        val input = "a".repeat(64) + "!"
        val started = System.nanoTime()
        val replaced = SecurityValidator.replaceBounded(Regex("^(a|aa)+\\1$"), input, "", timeoutMs = 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(replaced)
        assertTrue(elapsedMs < 5_000, "took $elapsedMs ms")
    }

    @Test
    fun `replaceBounded keeps group references in the replacement`() {
        val replaced = SecurityValidator.replaceBounded(Regex("(\\w+)\\s+(\\w+)"), "John Doe", "$2 $1")
        assertEquals("Doe John", replaced)
    }

    @Test
    fun `replaceFirstBounded gives up on catastrophic backtracking`() {
        val input = "a".repeat(64) + "!"
        val replaced = SecurityValidator.replaceFirstBounded(Regex("^(a|aa)+\\1$"), input, "", timeoutMs = 200)
        assertNull(replaced)
    }

    @Test
    fun `replaceFirstBounded replaces only the first match`() {
        assertEquals("X-2-3", SecurityValidator.replaceFirstBounded(Regex("\\d"), "1-2-3", "X"))
    }
}
