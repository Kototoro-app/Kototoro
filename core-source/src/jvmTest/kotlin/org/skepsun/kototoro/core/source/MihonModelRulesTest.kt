package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MihonModelRulesTest {
    @Test
    fun `content chapter and page identities separate sources parents and chapters`() {
        assertNotEquals(MihonModelRules.contentId("/same", "MIHON_1", "A"),
            MihonModelRules.contentId("/same", "MIHON_2", "A"))
        assertEquals(MihonModelRules.contentId("/same", "MIHON_1", "A"),
            MihonModelRules.contentId("/same", "MIHON_1", "B"))
        assertNotEquals(MihonModelRules.chapterId("/same", "MIHON_1", "/parent1"),
            MihonModelRules.chapterId("/same", "MIHON_1", "/parent2"))
        assertNotEquals(MihonModelRules.pageId("42", 0), MihonModelRules.pageId("43", 0))
    }

    @Test
    fun `blank content identity uses title then unknown and is nonnegative`() {
        assertEquals(MihonModelRules.contentId("", "MIHON_1", ""), MihonModelRules.contentId("unknown", "MIHON_1", ""))
        assertEquals(MihonModelRules.contentId("", "MIHON_1", "漫画"), MihonModelRules.contentId("漫画", "MIHON_1", ""))
        assertTrue(MihonModelRules.contentId("/漫画/😀", "MIHON_-9223372036854775808", "") >= 0)
    }

    @Test
    fun `relative cover URL resolution retains absolute protocol relative and ID URLs`() {
        assertNull(MihonModelRules.resolveUrl("https://a", " "))
        assertEquals("https://a/cover", MihonModelRules.resolveUrl("https://a/", "/cover"))
        assertEquals("https://b/cover", MihonModelRules.resolveUrl("https://a", "//b/cover"))
        assertEquals("http://b/cover", MihonModelRules.resolveUrl("https://a", "http://b/cover"))
        assertEquals("84652", MihonModelRules.resolveUrl("", "84652"))
    }

    @Test
    fun `cover Referer policy preserves declared and empty headers while retaining Android defaults`() {
        assertEquals("https://hitomi.la/", MihonModelRules.coverReferer("https://hitomi.la/cover", null))
        assertEquals("https://hitomi.la/",
            MihonModelRules.coverReferer("https://cdn.gold-usergeneratedcontent.net/cover", null))
        assertNull(MihonModelRules.coverReferer("https://fixture.invalid/cover", null))
        assertNull(MihonModelRules.coverReferer("https://hitomi.la/cover", "https://source.invalid/"))
        assertNull(MihonModelRules.coverReferer("https://hitomi.la/cover", ""))
    }

    @Test
    fun `manga URL cleanup strips only real base paths and leaves API IDs intact`() {
        assertEquals("84652", MihonModelRules.mangaUrl("https://a", "84652"))
        assertEquals("/path", MihonModelRules.mangaUrl("https://a/", "https://a/path"))
        assertEquals("https://ab/path", MihonModelRules.mangaUrl("https://a", "https://ab/path"))
        assertEquals("/path", MihonModelRules.mangaUrl("https://a", "https://ahttps//a/path"))
    }

    @Test
    fun `genre class representations drop fragments and retain ordinary tags`() {
        assertEquals("爱情", MihonModelRules.cleanGenre("ThemeInfo(name=爱情, pathWord=aiqing)"))
        assertEquals("", MihonModelRules.cleanGenre("pathWord=aiqing)"))
        assertEquals("Science Fiction", MihonModelRules.cleanGenre("Science Fiction"))
    }

    @Test
    fun `source and explicit content ratings precede safe and adult genre inference`() {
        assertEquals("ADULT", MihonModelRules.contentRating(true, "SAFE", listOf("safe")))
        assertEquals("SUGGESTIVE", MihonModelRules.contentRating(false, "SUGGESTIVE", listOf("safe")))
        assertEquals("SAFE", MihonModelRules.contentRating(false, null, listOf("safe", "nsfw")))
        assertEquals("ADULT", MihonModelRules.contentRating(false, null, listOf(" NSFW ")))
        assertNull(MihonModelRules.contentRating(false, null, listOf("Romance")))
    }
}
