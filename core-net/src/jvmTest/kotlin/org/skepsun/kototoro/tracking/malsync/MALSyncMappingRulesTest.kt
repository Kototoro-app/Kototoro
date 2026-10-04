package org.skepsun.kototoro.tracking.malsync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MALSyncMappingRulesTest {
    private fun entry(
        site: String = "Anilist",
        key: String = "12",
        id: String? = null,
        title: String? = null,
        url: String? = null,
    ) = MALSyncEntry(site, key, id, title, url)

    @Test
    fun `supported site names ignore case and unknown sites are omitted`() {
        val entries = listOf("MaL", "Anilist", "KITSU", "Shikimori", "Bangumi", "MangaUpdates", "Simkl", "unknown")
            .map { entry(site = it) }
        assertEquals(
            MALSyncService.entries.filter { it != MALSyncService.MAL },
            MALSyncMappingRules.map(entries, MALSyncService.MAL).map { it.service },
        )
    }

    @Test
    fun `source site is excluded regardless of casing`() {
        assertEquals(
            emptyList<MALSyncMapping>(),
            MALSyncMappingRules.map(listOf(entry(site = "aNiLiSt")), MALSyncService.ANILIST),
        )
    }

    @Test
    fun `identifier takes precedence and invalid identifiers fall back to entry key`() {
        val entries = listOf(
            entry(key = "1", id = "91"), entry(key = "2", id = ""), entry(key = "3", id = "  "),
            entry(key = "4", id = "wrong"), entry(key = "5", id = "9223372036854775808"),
            entry(key = "6", id = " 7 "), entry(key = "7", id = null),
        )
        assertEquals(listOf(91L, 2L, 3L, 4L, 5L, 6L, 7L),
            MALSyncMappingRules.map(entries, MALSyncService.MAL).map { it.remoteId })
    }

    @Test
    fun `invalid keys are skipped but valid identifiers do not need numeric keys`() {
        val entries = listOf(entry(key = "bad"), entry(key = " 12 "), entry(key = "overflow9223372036854775808"),
            entry(key = "slug", id = "8"))
        assertEquals(listOf(8L), MALSyncMappingRules.map(entries, MALSyncService.MAL).map { it.remoteId })
    }

    @Test
    fun `zero signed values and long boundaries retain legacy acceptance`() {
        val entries = listOf("0", "-1", "+2", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
            .map { entry(id = it) }
        assertEquals(listOf(0L, -1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE),
            MALSyncMappingRules.map(entries, MALSyncService.MAL).map { it.remoteId })
    }

    @Test
    fun `blank metadata becomes null but nonblank text is not trimmed`() {
        val result = MALSyncMappingRules.map(
            listOf(entry(key = "1", title = "  ", url = ""), entry(key = "2", title = " 葬送 ", url = " /path ")),
            MALSyncService.MAL,
        )
        assertNull(result[0].title)
        assertNull(result[0].url)
        assertEquals(" 葬送 ", result[1].title)
        assertEquals(" /path ", result[1].url)
    }

    @Test
    fun `duplicates keep first projected entry while same ids on different sites remain`() {
        val result = MALSyncMappingRules.map(
            listOf(entry(key = "a", id = "9", title = "first"), entry(key = "b", id = "9", title = "second"),
                entry(site = "Kitsu", id = "9")),
            MALSyncService.MAL,
        )
        assertEquals(listOf(MALSyncService.ANILIST, MALSyncService.KITSU), result.map { it.service })
        assertEquals("first", result.first().title)
    }

    @Test
    fun `only original four services can be queried`() {
        assertEquals(listOf("mal", "anilist", "kitsu", "shikimori", null, null),
            MALSyncService.entries.map { it.apiPath })
        assertEquals(listOf("manga", "anime"), MALSyncKind.entries.map { it.slug })
    }
}
