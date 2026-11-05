package org.skepsun.kototoro.core.parser.rule

import io.mockk.mockk
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.javascript.JavaScriptRuleParser
import java.time.Duration

class RuleEngineReplacementTest {

    private val engine = DefaultRuleEngine(RuleCache())
    private val enhancedEngine = EnhancedRuleEngine(
        baseRuleEngine = engine,
        jsRuleParser = JavaScriptRuleParser(mockk(relaxed = true)),
        jsonPathParser = JsonPathParser(),
        enhancedCssSelector = EnhancedCssSelector(),
    )

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

    @Test
    fun `extraction gives up on catastrophic backtracking`() {
        val document = Jsoup.parse("<div>${"a".repeat(64)}!</div>")

        assertEquals("aaa", enhancedEngine.parseField(document.body(), "div@text##@regex:(a{3})"))
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertEquals("", enhancedEngine.parseField(document.body(), "div@text##@regex:^(a|aa)+\\1$"))
        }
    }
}
