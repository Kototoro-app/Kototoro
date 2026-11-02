package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.source.contentTypeTitle
import org.skepsun.kototoro.core.ui.source.contentTypeTitleKey
import org.skepsun.kototoro.core.ui.text.UiStrings

/**
 * The Windows side of the shared text layer: a name a shared screen asks for reads Android's own catalog, which
 * `prepareDesktopStrings` exports, instead of a second copy kept in `desktop-strings.properties`.
 */
class DesktopUiStringsTest {

    /** Every content type the protocol carries, so a new one without text fails here instead of at runtime. */
    private val contentTypes = listOf(
        "MANGA", "MANHWA", "MANHUA", "COMICS", "NOVEL", "VIDEO", "ONE_SHOT", "DOUJINSHI", "IMAGE_SET",
        "ARTIST_CG", "GAME_CG", "HENTAI_MANGA", "HENTAI_NOVEL", "HENTAI_VIDEO", "OTHER",
    )

    @Test
    fun `content type titles come from Android's catalog`() {
        contentTypes.forEach { name ->
            val key = contentTypeTitleKey(name)
            val android = AndroidStrings[key]

            assertNotEquals(key, android, "$name has no $key in Android's exported catalog")
            assertEquals(android, DesktopUiStrings.contentTypeTitle(name), name)
        }
    }

    @Test
    fun `a type the catalog does not carry keeps its own name`() {
        assertEquals("SOMETHING_NEW", DesktopUiStrings.contentTypeTitle("SOMETHING_NEW"))
    }

    @Test
    fun `the shared text follows the interface language`() {
        val expected = if (DesktopStrings.currentTag.startsWith("zh")) "漫画" else "Manga"

        assertEquals(expected, DesktopUiStrings.string("content_type_manga"))
    }

    @Test
    fun `a name no catalog carries answers itself`() {
        assertEquals("desktop_missing_key_probe", DesktopUiStrings.string("desktop_missing_key_probe"))
    }

    @Test
    fun `formatting substitutes Android's placeholders`() {
        val page = DesktopUiStrings.format("reader_page_label", 7)

        assertTrue(page.contains("7"), page)
        assertFalse(page.contains('%'), page)
    }

    @Test
    fun `a catalog that answers nothing still composes`() {
        val none = object : UiStrings {
            override fun string(name: String): String = name
            override fun format(name: String, vararg args: Any?): String = name
        }

        assertEquals("MANGA", none.contentTypeTitle("MANGA"))
    }
}
