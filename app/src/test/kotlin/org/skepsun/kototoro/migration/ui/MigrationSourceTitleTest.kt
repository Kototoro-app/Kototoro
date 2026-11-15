package org.skepsun.kototoro.migration.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MigrationSourceTitleTest {

    @Test
    fun `numeric extension id is shortened behind its ecosystem`() {
        assertEquals("Mihon …0001", unresolvedSourceLabel("MIHON_7000000000000000001"))
        assertEquals("Aniyomi …4321", unresolvedSourceLabel("ANIYOMI_987654321"))
    }

    @Test
    fun `readable names are kept`() {
        assertEquals("MANGADEX", unresolvedSourceLabel("MANGADEX"))
        assertEquals("JSON_TVBOX_site", unresolvedSourceLabel("JSON_TVBOX_site"))
        assertEquals("HENTAI_2READ", unresolvedSourceLabel("HENTAI_2READ"))
        assertEquals("MIHON_42", unresolvedSourceLabel("MIHON_42"))
        assertEquals("_123456789", unresolvedSourceLabel("_123456789"))
    }
}
