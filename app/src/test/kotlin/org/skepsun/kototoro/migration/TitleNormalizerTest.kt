package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.TitleNormalizer

class TitleNormalizerTest {
    @Test
    fun `full width latin becomes lowercase half width without spaces or punctuation`() {
        assertEquals("onepiece", TitleNormalizer.normalize("ＯＮＥ　ＰＩＥＣＥ！"))
    }

    @Test
    fun `cjk letters are kept`() {
        assertEquals("葬送的芙莉莲", TitleNormalizer.normalize(" 葬送的芙莉莲 "))
    }

    @Test
    fun `punctuation and separators are dropped`() {
        assertEquals("sousounofrieren", TitleNormalizer.normalize("Sousou no Frieren: -"))
    }

    @Test
    fun `blank title normalizes to empty`() {
        assertEquals("", TitleNormalizer.normalize("  ・ "))
    }

    @Test
    fun `data flags round trip through bits`() {
        val flags = setOf(MigrationDataFlag.PROGRESS, MigrationDataFlag.NOTES)
        assertEquals(flags + MigrationDataFlag.CATEGORIES, MigrationDataFlag.fromBits(MigrationDataFlag.toBits(flags)))
    }
}
