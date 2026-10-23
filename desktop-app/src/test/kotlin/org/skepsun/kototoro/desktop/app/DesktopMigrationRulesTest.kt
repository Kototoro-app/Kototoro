package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef

class DesktopMigrationRulesTest {
    private val manga = SourceRef("fixture", "zh", "MANGA")
    private val novel = SourceRef("novel-fixture", "zh", "NOVEL")
    private val adult = SourceRef("adult-fixture", "zh", "HENTAI_MANGA")

    private fun listing(source: SourceRef, name: String = source.name.toString()) =
        SourceListing(source, name, SourceEcosystem.MIHON, supportsLatest = true)

    private fun content(id: Long, source: SourceRef, title: String = "Work $id") = SourceContent(
        id = id, title = title, altTitles = emptySet(), url = "/$id", publicUrl = "", rating = 0f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = source,
    )

    @Test
    fun `target sources keep the same content type and drop the work's own source`() {
        val sources = desktopMigrationSources(
            listOf(listing(manga), listing(novel), listing(SourceRef("other", "zh", "MANGA"))),
            content(1, manga),
        )
        assertEquals(listOf("other"), sources.map { it.source.name })
    }

    @Test
    fun `an adult work may still move to a plain manga source, and the reverse`() {
        val adultContent = content(2, adult)
        assertEquals(listOf("fixture", "other"), desktopMigrationSources(
            listOf(listing(manga), listing(SourceRef("other", "zh", "HENTAI_MANGA")), listing(adult)), adultContent,
        ).map { it.source.name })
        assertEquals(listOf("fixture"), desktopMigrationSources(
            listOf(listing(adult), listing(manga)), adultContent,
        ).map { it.source.name })
    }

    @Test
    fun `a source listed twice under two ecosystems is offered once`() {
        val other = SourceRef("other", "zh", "MANGA")
        val sources = desktopMigrationSources(
            listOf(listing(manga), listing(other, "Other"), listing(other, "Other copy")),
            content(3, manga),
        )
        assertEquals(listOf("other"), sources.map { it.source.name })
    }

    @Test
    fun `search results become candidates only on the target source and never as the same work`() {
        val old = content(7, manga)
        val items = listOf(content(7, manga), content(8, SourceRef("other", "zh", "MANGA")),
            content(9, manga), content(10, SourceRef("other", "zh", "NOVEL")))
        assertEquals(listOf(8L), desktopMigrationCandidates(items, "other", old).map { it.id })
    }

    @Test
    fun `the dialog reports the picked source name and refuses to migrate while searching`() {
        val sources = listOf(listing(manga), listing(SourceRef("other", "zh", "MANGA"), "Other source"))
        val state = DesktopMigrationState(contentId = 1, title = "Work", sources = sources,
            sourceName = "other", query = "Work")
        assertEquals("Other source", state.sourceLabel)
        assertTrue(state.canMigrate)
        assertFalse(state.copy(searching = true).canMigrate)
        assertFalse(state.copy(sourceName = null).canMigrate)
    }
}
