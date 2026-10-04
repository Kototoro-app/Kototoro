package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.NovelBlock.Heading
import org.skepsun.kototoro.desktop.runtime.NovelBlock.Image
import org.skepsun.kototoro.desktop.runtime.NovelBlock.Paragraph

class DesktopNovelHtmlTest {
    private fun paragraphs(html: String) = DesktopNovelHtml.parse(html).filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun `paragraphs headings rules and images become blocks in document order`() {
        val blocks = DesktopNovelHtml.parse(
            """<h2>第一章</h2><p>Hello <b>bold</b> and <em>slanted</em> world</p><hr><div>Second<br>third</div>
               <img data-src="/cover/1.png" src="placeholder.gif"><p><img src="https://x.test/a.png"></p>""",
            "https://site.test/novel/1",
        )
        assertEquals(Heading(2, "第一章"), blocks[0])
        val first = blocks[1] as Paragraph
        assertEquals("Hello bold and slanted world", first.text)
        assertTrue(first.runs.any { it.bold && it.text == "bold" } && first.runs.any { it.italic && it.text == "slanted" })
        assertTrue(first.runs.filter { !it.bold && !it.italic }.joinToString("") { it.text }.contains("Hello "))
        assertEquals(NovelBlock.Rule, blocks[2])
        assertEquals(listOf("Second", "third"), blocks.drop(3).filterIsInstance<Paragraph>().map { it.text })
        // Lazy-loading attributes win over placeholders, and relative addresses resolve against the page.
        assertEquals(listOf(Image("https://site.test/cover/1.png"), Image("https://x.test/a.png")), blocks.filterIsInstance<Image>())
    }

    @Test
    fun `plain text separates paragraphs by line breaks like the Android reader`() {
        assertEquals(listOf("第一段", "第二段", "第三段"), paragraphs("<p>第一段\n第二段\r\n\r\n  第三段  </p>"))
        assertEquals(listOf("a b"), paragraphs("<p>a    \t b</p>"))
    }

    @Test
    fun `scripts frames styles and event handlers never reach the reader`() {
        val html = """<p onclick="steal()">Safe</p><script>alert(1)</script><style>p{}</style>
            <iframe src="https://evil.test"></iframe><svg><text>x</text></svg><a href="javascript:evil()">link text</a>
            <img src="javascript:evil()"><img src="data:image/png;base64,AAAA">"""
        val blocks = DesktopNovelHtml.parse(html)
        assertEquals(listOf("Safe", "link text"), blocks.filterIsInstance<Paragraph>().map { it.text })
        assertTrue(blocks.none { it is Image }, "only http(s) images are accepted")
    }

    @Test
    fun `ideographic indentation survives and empty input yields nothing`() {
        assertEquals(listOf("　　缩进开头"), paragraphs("<p>　　缩进开头</p>"))
        assertTrue(DesktopNovelHtml.parse("").isEmpty())
        assertTrue(DesktopNovelHtml.parse("<p>   </p><p>&nbsp;</p>").isEmpty())
    }
}
