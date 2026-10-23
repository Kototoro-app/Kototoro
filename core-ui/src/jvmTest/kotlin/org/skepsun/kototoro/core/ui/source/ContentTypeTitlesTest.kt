package org.skepsun.kototoro.core.ui.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.text.UiStrings

/** The shared content-type wording: Android's resource names, and a name back when the catalog has none. */
class ContentTypeTitlesTest {

    private class Catalog(private val values: Map<String, String>) : UiStrings {
        override fun string(name: String): String = values[name] ?: name
        override fun format(name: String, vararg args: Any?): String = string(name)
    }

    private val android = Catalog(
        mapOf(
            "content_type_manga" to "Manga",
            "content_type_novel" to "Novel",
            "content_type_video" to "Video",
            "content_type_hentai_manga" to "Adult Manga",
            "content_type_image_set" to "Image set",
        ),
    )

    @Test
    fun `every parser type name maps to its own Android resource`() {
        assertEquals("content_type_manga", contentTypeTitleKey("MANGA"))
        assertEquals("content_type_hentai_manga", contentTypeTitleKey("HENTAI_MANGA"))
        assertEquals("content_type_image_set", contentTypeTitleKey("IMAGE_SET"))
        assertEquals("content_type_one_shot", contentTypeTitleKey("ONE_SHOT"))
        assertEquals("content_type_artist_cg", contentTypeTitleKey("ARTIST_CG"))
        assertEquals("content_type_game_cg", contentTypeTitleKey("GAME_CG"))
        assertEquals("content_type_manhwa", contentTypeTitleKey("MANHWA"))
        assertEquals("content_type_doujinshi", contentTypeTitleKey("DOUJINSHI"))
    }

    @Test
    fun `known types read the catalog`() {
        assertEquals("Manga", android.contentTypeTitle("MANGA"))
        assertEquals("Adult Manga", android.contentTypeTitle("HENTAI_MANGA"))
        assertEquals("Image set", android.contentTypeTitle("IMAGE_SET"))
    }

    @Test
    fun `a type the catalog does not have keeps its own name`() {
        assertEquals("COMICS", android.contentTypeTitle("COMICS"))
        assertEquals("SOMETHING_NEW", android.contentTypeTitle("SOMETHING_NEW"))
    }

    @Test
    fun `without an injected catalog the type name shows through`() {
        val titles = listOf("MANGA", "NOVEL", "VIDEO").map { name ->
            ContentTypeTitlesMissingCatalog.contentTypeTitle(name)
        }

        assertEquals(listOf("MANGA", "NOVEL", "VIDEO"), titles)
    }

    private object ContentTypeTitlesMissingCatalog : UiStrings {
        override fun string(name: String): String = name
        override fun format(name: String, vararg args: Any?): String = name
    }
}
