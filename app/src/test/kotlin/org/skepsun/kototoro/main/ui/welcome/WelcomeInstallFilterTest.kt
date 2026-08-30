package org.skepsun.kototoro.main.ui.welcome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

/**
 * Verifies the content-type filter that narrows the batch-install wizard plan
 * (and the install counts shown on its chips / summary).
 */
class WelcomeInstallFilterTest {

    // --- expandTypes ---------------------------------------------------------

    @Test
    fun `expandTypes returns empty set when nothing is selected`() {
        assertEquals(emptySet<ContentType>(), WelcomeInstallFilter.expandTypes(emptySet()))
    }

    @Test
    fun `expandTypes adds adult variants`() {
        assertEquals(
            setOf(ContentType.MANGA, ContentType.HENTAI_MANGA),
            WelcomeInstallFilter.expandTypes(setOf(ContentType.MANGA)),
        )
        assertEquals(
            setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO),
            WelcomeInstallFilter.expandTypes(setOf(ContentType.VIDEO)),
        )
    }

    @Test
    fun `expandTypes expands all selected types`() {
        assertEquals(
            setOf(
                ContentType.MANGA, ContentType.HENTAI_MANGA,
                ContentType.NOVEL, ContentType.HENTAI_NOVEL,
                ContentType.VIDEO, ContentType.HENTAI_VIDEO,
            ),
            WelcomeInstallFilter.expandTypes(
                setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
            ),
        )
    }

    // --- excludesKind --------------------------------------------------------

    @Test
    fun `excludesKind skips ecosystems whose content type is not selected`() {
        val videoOnly = WelcomeInstallFilter.expandTypes(setOf(ContentType.VIDEO))

        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.JAR, videoOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.MIHON, videoOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.LEGADO, videoOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.IREADER, videoOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.LNREADER, videoOnly))

        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.ANIYOMI, videoOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.CLOUDSTREAM, videoOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.TVBOX, videoOnly))
    }

    @Test
    fun `excludesKind keeps ecosystems overlapping any selected type`() {
        val mangaOnly = WelcomeInstallFilter.expandTypes(setOf(ContentType.MANGA))

        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.JAR, mangaOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.MIHON, mangaOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.IREADER, mangaOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.LNREADER, mangaOnly))
        assertFalse(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.TSUNDOKU, mangaOnly))

        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.LEGADO, mangaOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.ANIYOMI, mangaOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.CLOUDSTREAM, mangaOnly))
        assertTrue(WelcomeInstallFilter.excludesKind(UnifiedSourceKind.TVBOX, mangaOnly))
    }

    @Test
    fun `excludesKind never filters when no content type is selected`() {
        val noFilter = WelcomeInstallFilter.expandTypes(emptySet())

        UnifiedSourceKind.entries.forEach { kind ->
            assertFalse(WelcomeInstallFilter.excludesKind(kind, noFilter), "unexpected exclusion of $kind")
        }
    }
}
