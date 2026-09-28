package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.migration.domain.resolveContentFamily
import org.skepsun.kototoro.parsers.model.ContentType

class ContentFamilyResolverTest {
    @Test
    fun `known source type wins`() {
        assertEquals(ContentTypeFamily.NOVEL, resolveContentFamily(ContentType.NOVEL, "MANGA"))
    }

    @Test
    fun `unknown source type falls back to the stored type`() {
        assertEquals(ContentTypeFamily.VIDEO, resolveContentFamily(ContentType.OTHER, "HENTAI_VIDEO"))
    }

    @Test
    fun `missing or invalid stored type stays other`() {
        assertEquals(ContentTypeFamily.OTHER, resolveContentFamily(ContentType.OTHER, null))
        assertEquals(ContentTypeFamily.OTHER, resolveContentFamily(ContentType.OTHER, "garbage"))
    }
}
