package org.skepsun.kototoro.core.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.mihon.model.toKotoChapter
import org.skepsun.kototoro.mihon.model.toKotoContent
import org.skepsun.kototoro.mihon.model.toKotoPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.util.longHashCode

class MihonModelCompatibilityTest {
    private val source = MihonMangaSource(mockk<CatalogueSource>().also {
        every { it.id } returns 9007199254740993L
        every { it.lang } returns "zh"
        every { it.name } returns "Fixture"
    }, "fixture")

    @Test
    fun `shared identities match existing parser hashes including Unicode overflow and blank URLs`() {
        for (url in listOf("/manga", "84652", "/漫画/😀", "")) {
            val title = "漫画"
            val original = "${source.name}|manga|${url.ifBlank { title }}".longHashCode() and Long.MAX_VALUE
            assertEquals(original, MihonModelRules.contentId(url, source.name, title))
            val manga = SManga.create().apply { this.url = url; this.title = title }
            assertEquals(original, manga.toKotoContent(source).id)
        }
    }

    @Test
    fun `chapter and page adapters retain original parent scoped identity`() {
        val chapter = SChapter.create().apply { url = "/chapter"; name = "Chapter"; chapter_number = 2f }
        val result = chapter.toKotoChapter(source, parentUrl = "/parent")
        val original = "${source.name}|chapter|/parent|/chapter".hashCode().toLong() and Long.MAX_VALUE
        assertEquals(original, result.id)
        assertEquals("$original|page|7".hashCode().toLong() and Long.MAX_VALUE,
            Page(7, "/origin", "/image").toKotoPage(source, chapter, result.id).id)
    }

    @Test
    fun `shared rating and genre cleanup preserve Android mapping precedence`() {
        val manga = SManga.create().apply {
            url = "/manga"; title = "Fixture"
            genres = listOf("ThemeInfo(name=爱情, pathWord=aiqing)", "pathWord=aiqing)", "safe", "nsfw")
            contentRating = SManga.ContentRating.SAFE
        }
        val content = manga.toKotoContent(source)
        assertEquals(ContentRating.SAFE, content.contentRating)
        assertEquals(setOf("爱情", "safe", "nsfw"), content.tags.map { it.title }.toSet())
        assertEquals(ContentRating.ADULT, manga.toKotoContent(source.copy(isNsfw = true)).contentRating)
    }
}
