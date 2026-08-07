package org.skepsun.kototoro.settings.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.parsers.model.ContentType

class SuggestionSourceFilterTest {

    private val options = listOf(
        SuggestionSourceOption("MANGA_NATIVE", "Manga Native", ContentType.MANGA, SourceType.NATIVE),
        SuggestionSourceOption("JSON_LEGADO_BOOK", "Novel Legado", ContentType.NOVEL, SourceType.JSON_LEGADO),
        SuggestionSourceOption("CLOUDSTREAM_VIDEO", "Video Stream", ContentType.VIDEO, SourceType.CLOUDSTREAM),
    )

    @Test
    fun `empty filters include every source`() {
        assertEquals(options, filterSuggestionSourceOptions(options, "", emptySet(), emptySet()))
    }

    @Test
    fun `content and source type filters are combined`() {
        assertEquals(
            listOf(options[1]),
            filterSuggestionSourceOptions(
                options = options,
                query = "",
                contentTypes = setOf(ContentType.NOVEL, ContentType.VIDEO),
                sourceTypes = setOf(SourceType.JSON_LEGADO),
            ),
        )
    }

    @Test
    fun `query is combined with type filters and ignores case`() {
        assertEquals(
            listOf(options[2]),
            filterSuggestionSourceOptions(
                options = options,
                query = "stream",
                contentTypes = setOf(ContentType.VIDEO),
                sourceTypes = emptySet(),
            ),
        )
    }
}
