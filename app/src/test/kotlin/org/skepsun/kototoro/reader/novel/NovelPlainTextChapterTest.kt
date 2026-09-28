package org.skepsun.kototoro.reader.novel

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A local .txt chapter is plain text, but the loader handles every local chapter as HTML: it goes
 * through a Jsoup round trip (image src rewriting) and an HTML-to-text pass. Read as HTML, the blank
 * lines separating the paragraphs are insignificant whitespace, so a whole chapter collapsed into one
 * paragraph, and any `<` in the prose was taken for a tag.
 */
class NovelPlainTextChapterTest {

    private val chapter = "First paragraph.\n\nSecond paragraph,\nwith a line break.\r\n\r\n\r\nThird < fourth & fifth."

    @Test
    fun `blank lines become paragraphs and single newlines become line breaks`() {
        assertEquals(
            "<p>First paragraph.</p>\n<p>Second paragraph,<br>with a line break.</p>\n<p>Third &lt; fourth &amp; fifth.</p>",
            NovelHtmlNormalizer.fromPlainText(chapter),
        )
    }

    @Test
    fun `paragraphs survive the loader's Jsoup round trip`() {
        val roundTripped = Jsoup.parse(NovelHtmlNormalizer.fromPlainText(chapter)).outerHtml()
        val paragraphs = Jsoup.parse(roundTripped).select("p")
        assertEquals(3, paragraphs.size)
        assertEquals("First paragraph.", paragraphs[0].text())
        assertEquals(1, paragraphs[1].select("br").size)
        assertEquals("Third < fourth & fifth.", paragraphs[2].text())
        assertTrue(roundTripped.contains("<br>"))
    }

    @Test
    fun `angle brackets in prose are escaped rather than read as tags`() {
        val html = NovelHtmlNormalizer.fromPlainText("a <b>not bold</b> c")
        assertFalse(html.contains("<b>"))
        assertEquals("a <b>not bold</b> c", Jsoup.parse(html).text())
    }

    @Test
    fun `blank text yields no paragraphs`() {
        assertEquals("", NovelHtmlNormalizer.fromPlainText(" \n\n \r\n"))
    }
}
