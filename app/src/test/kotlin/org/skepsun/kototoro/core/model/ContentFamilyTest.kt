package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class ContentFamilyTest {
    @Test
    fun `manga like types share the manga family`() {
        assertEquals(ContentTypeFamily.MANGA, ContentType.MANHWA.contentFamily())
        assertEquals(ContentTypeFamily.MANGA, ContentType.HENTAI_MANGA.contentFamily())
    }

    @Test
    fun `novel and video families are distinct`() {
        assertEquals(ContentTypeFamily.NOVEL, ContentType.HENTAI_NOVEL.contentFamily())
        assertEquals(ContentTypeFamily.VIDEO, ContentType.HENTAI_VIDEO.contentFamily())
    }
}
