package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTag

class DesktopSuggestionFiltersTest {

    private val manga = SourceRef("MANGA_SITE", "zh", "MANGA")
    private val novels = SourceRef("NOVEL_SITE", "zh", "NOVEL")
    private val adult = SourceRef("ADULT_SITE", "zh", "HENTAI_MANGA")

    private fun tag(title: String, source: SourceRef = manga) = SourceTag(title, title.lowercase(), source)

    private fun work(
        id: Long,
        source: SourceRef = manga,
        vararg tags: String,
        contentRating: String? = null,
        title: String = "作品 $id",
    ) = SourceContent(
        id, title, emptySet(), "/w$id", "https://fixture.invalid/w$id", 0f, contentRating, null,
        tags.map { tag(it, source) }.toSet(), null, emptySet(), source,
    )

    @Test
    fun `chips are the top tags, the adult macros and the top sources in Android's order`() {
        val items = listOf(
            work(1, manga, "Romance", "Comedy"),
            work(2, manga, "Romance"),
            work(3, novels, "Romance", "Horror"),
            work(4, novels, "Horror"),
            work(5, novels, "Drama"),
        )

        assertEquals(
            listOf(
                DesktopSuggestionFilter.Tag("Romance"),
                DesktopSuggestionFilter.Tag("Horror"),
                DesktopSuggestionFilter.Tag("Comedy"),
                DesktopSuggestionFilter.Tag("Drama"),
                DesktopSuggestionFilter.Adult,
                DesktopSuggestionFilter.Safe,
                DesktopSuggestionFilter.Source(novels.name),
                DesktopSuggestionFilter.Source(manga.name),
            ),
            desktopSuggestionFilters(items),
        )
    }

    @Test
    fun `tag counts stop at five, sources at three, and adult chips follow the nsfw setting`() {
        val items = (1..4).map { index ->
            work(index.toLong(), if (index % 2 == 0) manga else novels, "Tag $index", "Shared")
        }

        val chips = desktopSuggestionFilters(items, includeAdultChips = false)
        assertEquals(
            listOf(
                DesktopSuggestionFilter.Tag("Shared"),
                DesktopSuggestionFilter.Tag("Tag 1"),
                DesktopSuggestionFilter.Tag("Tag 2"),
                DesktopSuggestionFilter.Tag("Tag 3"),
                DesktopSuggestionFilter.Tag("Tag 4"),
            ) + listOf(DesktopSuggestionFilter.Source(manga.name), DesktopSuggestionFilter.Source(novels.name)),
            chips,
        )
        assertEquals(5, desktopSuggestionFilters(items, tagLimit = 5, sourceLimit = 1).count { it is DesktopSuggestionFilter.Tag })
        assertEquals(1, desktopSuggestionFilters(items, sourceLimit = 1).count { it is DesktopSuggestionFilter.Source })
        assertTrue(desktopSuggestionFilters(emptyList()).isEmpty())
    }

    @Test
    fun `one work counts a repeated tag once and ties keep the chip row stable`() {
        val items = listOf(
            // The same tag twice with different spellings is one vote for Romance.
            SourceContent(1, "作品 1", emptySet(), "/w1", "https://fixture.invalid/w1", 0f, null, null,
                setOf(tag("Romance"), tag(" romance ")), null, emptySet(), manga),
            work(2, manga, "Comedy"),
            work(3, manga, "Comedy"),
        )

        assertEquals(
            listOf(DesktopSuggestionFilter.Tag("Comedy"), DesktopSuggestionFilter.Tag("Romance")),
            desktopSuggestionFilters(items, includeAdultChips = false).filterIsInstance<DesktopSuggestionFilter.Tag>(),
        )
    }

    @Test
    fun `chips are ANDed and an empty set narrows nothing`() {
        val items = listOf(
            work(1, manga, "Romance", "Comedy"),
            work(2, manga, "Romance"),
            work(3, novels, "Romance"),
            work(4, adult, "Romance", contentRating = "ADULT"),
        )
        val romance = DesktopSuggestionFilter.Tag("Romance")
        val source = DesktopSuggestionFilter.Source(manga.name)

        assertSame(items, desktopSuggestedContents(items, emptySet()))
        assertEquals(listOf(1L, 2L, 3L, 4L), desktopSuggestedContents(items, setOf(romance)).map { it.id })
        assertEquals(listOf(1L, 2L), desktopSuggestedContents(items, setOf(romance, source)).map { it.id })
        assertEquals(listOf(4L), desktopSuggestedContents(items, setOf(DesktopSuggestionFilter.Adult)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), desktopSuggestedContents(items, setOf(DesktopSuggestionFilter.Safe)).map { it.id })
        // Android's SQL ANDs the two opposite macros into an empty result too.
        assertTrue(items.none { it.acceptsSuggestionFilters(setOf(DesktopSuggestionFilter.Adult, DesktopSuggestionFilter.Safe)) })
    }

    @Test
    fun `tag titles match regardless of case and spacing`() {
        val item = work(1, manga, "Romance")

        assertTrue(item.acceptsSuggestionFilters(setOf(DesktopSuggestionFilter.Tag(" romance "))))
        assertFalse(item.acceptsSuggestionFilters(setOf(DesktopSuggestionFilter.Tag("Drama"))))
    }
}
