package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef

class DesktopShareLinksTest {
    private fun content(id: Long, title: String, publicUrl: String, url: String = "/inner/$id") =
        SourceContent(id = id, title = title, altTitles = emptySet(), url = url, publicUrl = publicUrl, rating = 0f,
            contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
            source = SourceRef("one", "zh", "MANGA"))

    @Test
    fun `one work pastes its title and link the way Android's single share does`() {
        val share = desktopShareText(listOf(content(1, "Title", "https://example.invalid/work/1")))
        assertEquals(1, share.count)
        assertFalse(share.isEmpty)
        assertEquals("Title\n \nhttps://example.invalid/work/1", share.text)
    }

    @Test
    fun `several works paste one title-link line each, skipping works without a link`() {
        val share = desktopShareText(listOf(
            content(1, "First", "https://example.invalid/work/1"),
            content(2, "Local", publicUrl = "", url = ""),
            content(3, "Third", "https://example.invalid/work/3"),
        ))
        assertEquals(2, share.count)
        assertEquals(
            "First - https://example.invalid/work/1\nThird - https://example.invalid/work/3",
            share.text,
        )
    }

    @Test
    fun `a source that only fills the inner url still shares it, and no links at all is empty`() {
        assertEquals("/inner/4", desktopShareText(listOf(content(4, "Only url", publicUrl = ""))).text
            .substringAfter("\n \n"))
        val blank = content(5, "Nothing", publicUrl = "  ", url = " ")
        val share = desktopShareText(listOf(blank))
        assertTrue(share.isEmpty)
        assertEquals("", share.text)
        assertEquals(0, desktopShareText(emptyList()).count)
    }
}
