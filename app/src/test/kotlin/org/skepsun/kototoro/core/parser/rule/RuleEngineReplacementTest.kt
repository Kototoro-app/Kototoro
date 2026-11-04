package org.skepsun.kototoro.core.parser.rule

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration

class RuleEngineReplacementTest {

    private val engine = DefaultRuleEngine(RuleCache())

    @Test
    fun `replacement removes all matches without changing surrounding text`() {
        val document = Jsoup.parse("<div>Chapter 12 - Volume 3</div>")

        assertEquals("Chapter  - Volume ", engine.parseField(document, "div@text##[0-9]+"))
    }

    @Test
    fun `replacement preserves text when there is no match`() {
        val document = Jsoup.parse("<div>Kototoro</div>")

        assertEquals("Kototoro", engine.parseField(document, "div@text##[0-9]+"))
    }

    @Test
    fun `replacement gives up on catastrophic backtracking through the rule entry point`() {
        val document = Jsoup.parse("<div>${"a".repeat(64)}!</div>")

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertEquals("", engine.parseField(document, "div@text##^(a|aa)+\\1$"))
        }
    }
}
