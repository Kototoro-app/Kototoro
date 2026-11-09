package org.skepsun.kototoro.core.parser.legado

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnalyzeByRegexBoundsTest {

    // The JDK memoizes nested loops like (a+)+, so it needs a pattern it really backtracks on to prove
    // the bound; unbounded, this one takes seconds at 28 characters and far longer at 40.
    private val evil = arrayOf("(.*a){20}$")
    private val input = "a".repeat(40) + "!"

    @Test
    fun `a backtracking rule gives up within the budget`() {
        val started = System.nanoTime()

        AnalyzeByRegex.getElement(input, evil) shouldBe null
        AnalyzeByRegex.getElements(input, evil) shouldBe emptyList()

        (System.nanoTime() - started) / 1_000_000 shouldBeLessThan 6_000L
    }

    @Test
    fun `a backtracking step after a matching one gives up as well`() {
        val started = System.nanoTime()

        AnalyzeByRegex.getElements("<b>$input</b>", arrayOf("<b>.*</b>", "(.*a){20}$")) shouldBe emptyList()

        (System.nanoTime() - started) / 1_000_000 shouldBeLessThan 6_000L
    }

    @Test
    fun `ordinary rules still return every group`() {
        val html = "<a href=\"/1\">One</a><a href=\"/2\">Two</a>"

        AnalyzeByRegex.getElement(html, arrayOf("<a href=\"([^\"]+)\">([^<]+)</a>")) shouldBe
            listOf("<a href=\"/1\">One</a>", "/1", "One")
        AnalyzeByRegex.getElements(html, arrayOf("<a href=\"([^\"]+)\">([^<]+)</a>")) shouldBe listOf(
            listOf("<a href=\"/1\">One</a>", "/1", "One"),
            listOf("<a href=\"/2\">Two</a>", "/2", "Two"),
        )
    }

    @Test
    fun `steps joined by && run on the previous step's matches`() {
        val text = "x[1]y[2]z"

        AnalyzeByRegex.getElements(text, arrayOf("\\[\\d]", "\\d")) shouldBe listOf(listOf("1"), listOf("2"))
        AnalyzeByRegex.getElement(text, arrayOf("q+", "\\d")) shouldBe null
    }
}
