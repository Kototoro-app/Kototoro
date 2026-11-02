package org.skepsun.kototoro.space.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomSpacesTest {

    private val space = CustomSpace("custom:a", "日漫", 3, true, setOf("MANGA", "HENTAI_MANGA"), setOf("ja"), emptySet())

    @Test
    fun dimensionsAreAndedAndEmptyOnesUnrestricted() {
        assertTrue(space.admits("MANGA", "ja", "MIHON"))
        assertTrue(space.admits("HENTAI_MANGA", "JA-jp", "NATIVE"))
        assertFalse(space.admits("MANGA", "en", "MIHON"))
        assertFalse(space.admits("NOVEL", "ja", "MIHON"))
        assertEquals("MANGA", space.mediaKind)
        assertNull(space.copy(contentTypes = setOf("MANGA", "VIDEO")).mediaKind)
    }

    @Test
    fun androidsStoredFormRoundTrips() {
        val entity = space.copy(sourceKinds = setOf("MIHON", "ANIYOMI")).toEntity(1, 2)
        assertEquals("HENTAI_MANGA,MANGA", entity.contentTypes)
        assertEquals("ANIYOMI,MIHON", entity.sourceKinds)
        assertEquals(space.copy(sourceKinds = setOf("MIHON", "ANIYOMI")), CustomSpace.from(entity))
    }
}
