package org.skepsun.kototoro.core.ui.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parser both hosts render release notes with. Every case here is something GitHub's own notes, a hand-written
 * changelog or the app's update dialog actually produces.
 */
class MarkdownBlocksTest {

    @Test
    fun `headings keep their level and drop closing hashes`() {
        val blocks = parseMarkdownBlocks("# Version 2.3.1\n\n### Fixes ###\n")

        assertEquals(
            listOf(MarkdownBlock.Heading(1, "Version 2.3.1"), MarkdownBlock.Heading(3, "Fixes")),
            blocks,
        )
    }

    @Test
    fun `a hash without a space stays text`() {
        val blocks = parseMarkdownBlocks("#hashtag is not a heading")

        assertEquals(listOf(MarkdownBlock.Paragraph("#hashtag is not a heading")), blocks)
    }

    @Test
    fun `soft breaks join with a space and hard breaks stay newlines`() {
        val soft = parseMarkdownBlocks("first line\nsecond line")
        val hard = parseMarkdownBlocks("first line  \nsecond line")

        assertEquals(listOf(MarkdownBlock.Paragraph("first line second line")), soft)
        assertEquals(listOf(MarkdownBlock.Paragraph("first line\nsecond line")), hard)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = parseMarkdownBlocks("one\n\ntwo\n\n\ntwo follows")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("one"),
                MarkdownBlock.Paragraph("two"),
                MarkdownBlock.Paragraph("two follows"),
            ),
            blocks,
        )
    }

    @Test
    fun `bullets nest by two-space indents and task items keep their box`() {
        val blocks = parseMarkdownBlocks("- top\n  - nested\n    - deeper\n* star\n+ plus\n- [x] done\n- [ ] todo")

        assertEquals(
            listOf(
                MarkdownBlock.ListItem("•", "top"),
                MarkdownBlock.ListItem("•", "nested", depth = 1),
                MarkdownBlock.ListItem("•", "deeper", depth = 2),
                MarkdownBlock.ListItem("•", "star"),
                MarkdownBlock.ListItem("•", "plus"),
                MarkdownBlock.ListItem("☑", "done"),
                MarkdownBlock.ListItem("☐", "todo"),
            ),
            blocks,
        )
    }

    @Test
    fun `ordered items keep their numbers`() {
        val blocks = parseMarkdownBlocks("1. first\n2) second\n10. tenth")

        assertEquals(
            listOf(
                MarkdownBlock.ListItem("1.", "first"),
                MarkdownBlock.ListItem("2.", "second"),
                MarkdownBlock.ListItem("10.", "tenth"),
            ),
            blocks,
        )
    }

    @Test
    fun `fenced code keeps its text verbatim and records the language`() {
        val note = "before\n\n```kotlin\nval a = 1\n\n# not a heading\n```\nafter"

        val blocks = parseMarkdownBlocks(note)

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("before"),
                MarkdownBlock.Code("kotlin", "val a = 1\n\n# not a heading"),
                MarkdownBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `an unclosed fence keeps the rest as code`() {
        val blocks = parseMarkdownBlocks("```\nline one\nline two")

        assertEquals(listOf(MarkdownBlock.Code(null, "line one\nline two")), blocks)
    }

    @Test
    fun `rules are recognized in every form`() {
        val blocks = parseMarkdownBlocks("---\n\n***\n\n_ _ _")

        assertEquals(listOf(MarkdownBlock.Rule, MarkdownBlock.Rule, MarkdownBlock.Rule), blocks)
    }

    @Test
    fun `quoted lines merge into one quote`() {
        val blocks = parseMarkdownBlocks("> first\n> second\n\nafter")

        assertEquals(listOf(MarkdownBlock.Quote("first second"), MarkdownBlock.Paragraph("after")), blocks)
    }

    @Test
    fun `html comments never reach the screen`() {
        val blocks = parseMarkdownBlocks("<!-- release notes -->\n\n## Notes\n\n<!--\nmulti\nline\n-->\nbody")

        assertEquals(listOf(MarkdownBlock.Heading(2, "Notes"), MarkdownBlock.Paragraph("body")), blocks)
    }

    @Test
    fun `inline emphasis, code and links become runs`() {
        val spans = parseMarkdownInline("Fix **big** bug, *maybe* `code`, ~~old~~ and [docs](https://example.org/a)")

        assertEquals(
            listOf(
                MarkdownSpan.Text("Fix "),
                MarkdownSpan.Text("big", bold = true),
                MarkdownSpan.Text(" bug, "),
                MarkdownSpan.Text("maybe", italic = true),
                MarkdownSpan.Text(" "),
                MarkdownSpan.Text("code", code = true),
                MarkdownSpan.Text(", "),
                MarkdownSpan.Text("old", strikeThrough = true),
                MarkdownSpan.Text(" and "),
                MarkdownSpan.Link("docs", "https://example.org/a"),
            ),
            spans,
        )
    }

    @Test
    fun `a link title and an image alt both keep the target`() {
        val titled = parseMarkdownInline("[build](https://ci.example/x \"Build 12\")")
        val image = parseMarkdownInline("![logo](https://example.org/logo.png)")

        assertEquals(listOf(MarkdownSpan.Link("build", "https://ci.example/x")), titled)
        assertEquals(listOf(MarkdownSpan.Link("logo", "https://example.org/logo.png")), image)
    }

    @Test
    fun `snake_case stays literal and underscores on word boundaries italicize`() {
        assertEquals(
            listOf(MarkdownSpan.Text("name_of_thing")),
            parseMarkdownInline("name_of_thing"),
        )
        assertEquals(
            listOf(MarkdownSpan.Text("("), MarkdownSpan.Text("emphasis", italic = true), MarkdownSpan.Text(")")),
            parseMarkdownInline("(_emphasis_)"),
        )
    }

    @Test
    fun `unclosed markers and escapes stay literal`() {
        assertEquals(listOf(MarkdownSpan.Text("2 * 3")), parseMarkdownInline("2 * 3"))
        assertEquals(listOf(MarkdownSpan.Text("**bold")), parseMarkdownInline("**bold"))
        assertEquals(listOf(MarkdownSpan.Text("* literal *")), parseMarkdownInline("\\* literal *"))
    }

    @Test
    fun `an html line break becomes a newline and other tags disappear`() {
        assertEquals(
            listOf(MarkdownSpan.Text("one\ntwo plain")),
            parseMarkdownInline("one<br>two <b>plain</b>"),
        )
    }

    @Test
    fun `a github release note parses into headings, bullets and links`() {
        val note = """
            <!-- Release notes generated using configuration in .github/release.yml -->
            ## What's Changed
            * Fix reader state by @someone in https://github.com/Kototoro-app/Kototoro/pull/1
            * **Windows**: keep the update channel by @other in https://github.com/Kototoro-app/Kototoro/pull/2

            **Full Changelog**: https://github.com/Kototoro-app/Kototoro/compare/v2.3.0...v2.3.1
        """.trimIndent()

        val blocks = parseMarkdownBlocks(note)

        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, "What's Changed"),
                MarkdownBlock.ListItem(
                    "•",
                    "Fix reader state by @someone in https://github.com/Kototoro-app/Kototoro/pull/1",
                ),
                MarkdownBlock.ListItem(
                    "•",
                    "**Windows**: keep the update channel by @other in https://github.com/Kototoro-app/Kototoro/pull/2",
                ),
                MarkdownBlock.Paragraph(
                    "**Full Changelog**: https://github.com/Kototoro-app/Kototoro/compare/v2.3.0...v2.3.1",
                ),
            ),
            blocks,
        )
        val second = (blocks[2] as MarkdownBlock.ListItem).text
        assertTrue(parseMarkdownInline(second).any { it is MarkdownSpan.Text && it.bold })
    }

    @Test
    fun `empty input produces no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks("\n\n   \n"))
        assertEquals(emptyList<MarkdownSpan>(), parseMarkdownInline(""))
    }
}
