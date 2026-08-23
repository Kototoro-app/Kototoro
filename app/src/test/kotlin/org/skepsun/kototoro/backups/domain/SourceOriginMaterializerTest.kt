package org.skepsun.kototoro.backups.domain

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

class SourceOriginMaterializerTest {

    @Test
    fun `known stable prefixes map to stable kinds`() {
        assertEquals("MIHON", SourceOriginMaterializer.kindForSourceKey("MIHON_123"))
        assertEquals("ANIYOMI", SourceOriginMaterializer.kindForSourceKey("ANIYOMI_123"))
        assertEquals("IREADER", SourceOriginMaterializer.kindForSourceKey("IREADER_123"))
        assertEquals("TSUNDOKU", SourceOriginMaterializer.kindForSourceKey("TSUNDOKU_9001"))
    }

    @Test
    fun `unknown prefix is never guessed`() {
        assertNull(SourceOriginMaterializer.kindForSourceKey("WEIRD_XYZ"))
        assertNull(SourceOriginMaterializer.kindForSourceKey("mihon_123"))
    }

    @Test
    fun `minimal origin for known prefix without installed source has kind only`() {
        val origin = SourceOriginMaterializer.minimalOrigin("TSUNDOKU_9001", installed = null, now = 42L)

        assertEquals("TSUNDOKU", origin.kind)
        assertEquals("TSUNDOKU_9001", origin.sourceKey)
        assertNull(origin.displayName)
        assertNull(origin.contentType)
        assertNull(origin.packageName)
        assertNull(origin.repositoryUrl)
        assertEquals(42L, origin.updatedAt)
    }

    @Test
    fun `minimal origin for unknown prefix is UNKNOWN without package guessing`() {
        val origin = SourceOriginMaterializer.minimalOrigin("WEIRD_XYZ", installed = null, now = 1L)

        assertEquals("UNKNOWN", origin.kind)
        assertNull(origin.packageName)
    }

    @Test
    fun `installed mihon source enriches display name content type and package name`() {
        val installed = mockk<MihonMangaSource> {
            every { displayName } returns "Mihon Test"
            every { name } returns "MIHON_101"
            every { contentType } returns org.skepsun.kototoro.parsers.model.ContentType.MANGA
            every { packageName } returns "eu.kanade.tachiyomi.en.test"
        }

        val origin = SourceOriginMaterializer.minimalOrigin("MIHON_101", installed, now = 5L)

        assertEquals("MIHON", origin.kind)
        assertEquals("Mihon Test", origin.displayName)
        assertEquals("MANGA", origin.contentType)
        assertEquals("eu.kanade.tachiyomi.en.test", origin.packageName)
    }

    @Test
    fun `installed tsundoku novel source enriches novel content type`() {
        val installed = mockk<TsundokuNovelSource> {
            every { displayName } returns "NovelFull (TS)"
            every { name } returns "TSUNDOKU_9001"
            every { contentType } returns org.skepsun.kototoro.parsers.model.ContentType.NOVEL
            every { packageName } returns "eu.kanade.tachiyomi.novelextension.en.novelfull"
        }

        val origin = SourceOriginMaterializer.minimalOrigin("TSUNDOKU_9001", installed, now = 5L)

        assertEquals("TSUNDOKU", origin.kind)
        assertEquals("NOVEL", origin.contentType)
        assertEquals("eu.kanade.tachiyomi.novelextension.en.novelfull", origin.packageName)
    }
}
