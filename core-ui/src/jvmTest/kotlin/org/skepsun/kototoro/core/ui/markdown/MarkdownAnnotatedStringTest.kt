package org.skepsun.kototoro.core.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The flattening [MarkdownText] uses to render inside a single text block - the details description, which limits its
 * own lines for the "show more" cut. It has to keep what the block renderer shows: emphasis, code, bullets and links.
 */
class MarkdownAnnotatedStringTest {

    private val colors = MarkdownColors(
        link = Color.Blue,
        quote = Color.Gray,
        rule = Color.LightGray,
        codeBackground = Color.White,
    )
    private val style = TextStyle()

    private fun flatten(markdown: String) = markdownAnnotatedString(
        blocks = parseMarkdownBlocks(markdownKeepingLineBreaks(markdown)),
        style = style,
        colors = colors,
    )

    @Test
    fun `a description keeps its own line breaks`() {
        val annotation = flatten("**Year:** 2019\n**Status:** Ongoing")

        assertEquals("Year: 2019\nStatus: Ongoing", annotation.text)
        val bold = annotation.spanStyles.filter { it.item.fontWeight != null }
        assertEquals(listOf(FontWeight.SemiBold, FontWeight.SemiBold), bold.map { it.item.fontWeight })
        assertEquals(
            listOf("Year:", "Status:"),
            bold.map { annotation.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun `release notes keep their blocks apart with one line break each`() {
        val annotation = flatten("## What's new\n\n- fixes\n- speed\n")

        assertEquals("What's new\n• fixes\n• speed", annotation.text)
    }

    @Test
    fun `nested list items keep their indentation`() {
        val annotation = flatten("- top\n  - nested")

        assertEquals("• top\n  • nested", annotation.text)
    }

    @Test
    fun `links stay tappable`() {
        val annotation = flatten("Read on [AniList](https://anilist.co)")

        assertEquals("Read on AniList", annotation.text)
        val link = annotation.getLinkAnnotations(0, annotation.length).single()
        assertEquals("https://anilist.co", (link.item as LinkAnnotation.Url).url)
        assertEquals("AniList", annotation.text.substring(link.start, link.end))
    }

    @Test
    fun `code spans are monospaced`() {
        val annotation = flatten("run `gradlew test` first")

        assertEquals("run gradlew test first", annotation.text)
        assertTrue(annotation.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `a rule becomes box drawing characters`() {
        val annotation = flatten("above\n\n---\n\nbelow")

        assertEquals("above\n${"\u2500".repeat(12)}\nbelow", annotation.text)
    }

    @Test
    fun `blank lines and already hard-broken lines are left alone`() {
        assertEquals("a  \n\nb  \nc  ", markdownKeepingLineBreaks("a\n\nb\nc  "))
        assertEquals("no break", markdownKeepingLineBreaks("no break"))
    }
}
