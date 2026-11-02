package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.*

class SourceRecoveryRulesTest {
    @Test
    fun `counts unique works across favourites and history without crossing ecosystems`() {
        val missing = SourceRecoveryRules.missing(listOf(
            RecoveryReference("MIHON_42", "one", true),
            RecoveryReference("MIHON_42", "one", true),
            RecoveryReference("MIHON_42", "one", false),
            RecoveryReference("MIHON_42", "two", false),
            RecoveryReference("ANIYOMI_42", "video", true),
            RecoveryReference("TSUNDOKU_42", "novel", true),
            RecoveryReference("LOCAL", "local", true),
            RecoveryReference("LOCAL_VIDEO", "local-video", false),
        ), setOf("ANIYOMI_42"))
        assertEquals(listOf(MissingLibrarySource("MIHON_42", 2, 1, 2),
            MissingLibrarySource("TSUNDOKU_42", 1, 1, 0)), missing)
    }

    @Test
    fun `one covering package replaces redundant alternatives with stable ties`() {
        val missing = listOf(MissingLibrarySource("MIHON_1", 1, 1, 0), MissingLibrarySource("MIHON_2", 1, 0, 1))
        val packages = listOf(RecoveryPackage("one", listOf("MIHON_1")),
            RecoveryPackage("bundle-z", listOf("MIHON_1", "MIHON_2")),
            RecoveryPackage("bundle-a", listOf("MIHON_1", "MIHON_2")),
            RecoveryPackage("wrong-ecosystem", listOf("ANIYOMI_2")))
        assertEquals(listOf("bundle-a"), SourceRecoveryRules.recommended(missing, packages))
        assertEquals(listOf("bundle-a"), SourceRecoveryRules.recommended(missing, packages.reversed()))
    }

    @Test
    fun `unmatched sources and empty catalogs remain unresolved`() {
        val missing = listOf(MissingLibrarySource("JSON_TVBOX_site", 2, 1, 1))
        assertEquals(emptyList<String>(), SourceRecoveryRules.recommended(missing, emptyList()))
        assertEquals(emptyList<String>(), SourceRecoveryRules.recommended(missing,
            listOf(RecoveryPackage("same-name", listOf("MIHON_1")))))
    }
}
