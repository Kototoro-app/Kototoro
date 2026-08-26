package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MihonForkBuiltinSourcesTest {

    @Test
    fun `TachiyomiSY bundled EH and EXH ids map to the native EXHENTAI parser`() {
        assertEquals(
            "EXHENTAI",
            MihonForkBuiltinSources.nativeSourceNameForId(6901L),
        )
        assertEquals(
            "EXHENTAI",
            MihonForkBuiltinSources.nativeSourceNameForId(6902L),
        )
    }

    @Test
    fun `Komikku built-in all-variant EH and EXH ids map to the native EXHENTAI parser`() {
        assertEquals(
            "EXHENTAI",
            MihonForkBuiltinSources.nativeSourceNameForId(1_713_178_126_840_476_467L),
        )
        assertEquals(
            "EXHENTAI",
            MihonForkBuiltinSources.nativeSourceNameForId(6_225_928_719_850_211_219L),
        )
    }

    @Test
    fun `unknown or unmapped ids stay unmapped so the raw key can be preserved`() {
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(0L))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(999_999L))
        // A real Mihon extension id must never be treated as a fork built-in.
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(8_392_556_173_882_593_881L))
        // Neko's MangaDex ids are handled via URL inference, not the id table.
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(2_499_283_573_021_220_255L))
        // Bundled lewd sources without a native Kototoro equivalent stay unmapped.
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(MihonForkBuiltinSources.SY_LEWD_PURURIN_ID))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(MihonForkBuiltinSources.SY_LEWD_TSUMINO_ID))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(MihonForkBuiltinSources.SY_LEWD_EIGHTMUSES_ID))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForId(MihonForkBuiltinSources.SY_LEWD_MERGED_ID))
    }

    @Test
    fun `E-Hentai gallery URLs map to EXHENTAI`() {
        assertEquals("EXHENTAI", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/g/123456/abc/"))
        assertEquals("EXHENTAI", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("g/123456/abc/"))
        assertEquals("EXHENTAI", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("https://e-hentai.org/g/123456/abc/"))
        assertEquals("EXHENTAI", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("https://exhentai.org/g/123456/abc/"))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/gallery/not-an-id/"))
    }

    @Test
    fun `MangaDex URLs map to MANGADEX covering Neko`() {
        assertEquals("MANGADEX", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/title/abcdef12-3456-7890-abcd-ef1234567890"))
        assertEquals("MANGADEX", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("title/abc/extra"))
        assertEquals("MANGADEX", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("https://mangadex.org/title/abc"))
        assertEquals("MANGADEX", MihonForkBuiltinSources.nativeSourceNameForMihonUrl("https://mangadex.org/manga/abc"))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/titles/abc"))
    }

    @Test
    fun `unrelated URLs and blanks are left untouched`() {
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl(null))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl(""))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/manga/luffy-is-cool"))
        assertNull(MihonForkBuiltinSources.nativeSourceNameForMihonUrl("/comics/123"))
    }
}
