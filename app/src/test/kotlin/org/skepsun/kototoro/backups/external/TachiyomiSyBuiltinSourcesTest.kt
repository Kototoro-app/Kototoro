package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TachiyomiSyBuiltinSourcesTest {

    @Test
    fun `TachiyomiSY bundled EH and EXH ids map to the native EXHENTAI parser`() {
        assertEquals("EXHENTAI", TachiyomiSyBuiltinSources.nativeSourceNameForId(6901L))
        assertEquals("EXHENTAI", TachiyomiSyBuiltinSources.nativeSourceNameForId(6902L))
    }

    @Test
    fun `unknown or unmapped ids stay unmapped so the raw key can be preserved`() {
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(0L))
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(999_999L))
        // A real Mihon extension id must never be treated as a TachiyomiSY built-in.
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(8_392_556_173_882_593_881L))
        // Bundled TachiyomiSY sources without a native Kototoro equivalent stay unmapped.
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(TachiyomiSyBuiltinSources.PURURIN_ID))
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(TachiyomiSyBuiltinSources.TSUMINO_ID))
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(TachiyomiSyBuiltinSources.EIGHTMUSES_ID))
        assertNull(TachiyomiSyBuiltinSources.nativeSourceNameForId(TachiyomiSyBuiltinSources.MERGED_ID))
    }
}
