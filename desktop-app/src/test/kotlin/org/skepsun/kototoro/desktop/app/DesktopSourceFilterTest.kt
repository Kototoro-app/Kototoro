package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag

class DesktopSourceFilterTest {
    private fun listing(name: String, ecosystem: SourceEcosystem, type: String) =
        SourceListing(SourceRef(name, "zh", type), name, ecosystem, supportsLatest = true)

    private val sources = listOf(
        listing("komiic", SourceEcosystem.KOTOTORO, "MANGA"),
        listing("uma-site", SourceEcosystem.UMA, "MANHUA"),
        listing("mangadex", SourceEcosystem.MIHON, "MANGA"),
        listing("allnovel", SourceEcosystem.TSUNDOKU, "NOVEL"),
    )

    @Test
    fun `parser plugins count as built-in and only Windows ecosystems are offered`() {
        assertEquals(listOf(SourceTag.BUILTIN, SourceTag.MIHON, SourceTag.ANIYOMI, SourceTag.CLOUDSTREAM, SourceTag.TSUNDOKU),
            DesktopSourceTagEntries)
        assertEquals(setOf(SourceTag.BUILTIN, SourceTag.MIHON, SourceTag.TSUNDOKU), DesktopSourceFilter.enabledTags(sources))
        assertEquals(setOf(BrowseGroupTab.All, BrowseGroupTab.Content, BrowseGroupTab.Novel),
            DesktopSourceFilter.enabledTabs(sources))
        val builtIn = DesktopSourceFilter(tags = setOf(SourceTag.BUILTIN))
        assertEquals(listOf("komiic", "uma-site"), sources.filter(builtIn::accepts).map { it.source.name })
        val novelsOrMihon = DesktopSourceFilter(tags = setOf(SourceTag.MIHON, SourceTag.TSUNDOKU))
        assertEquals(listOf("mangadex", "allnovel"), sources.filter(novelsOrMihon::accepts).map { it.source.name })
        val manga = DesktopSourceFilter(tab = BrowseGroupTab.Content)
        assertEquals(listOf("komiic", "uma-site", "mangadex"), sources.filter(manga::accepts).map { it.source.name })
        assertEquals(2, DesktopSourceFilter(setOf(SourceTag.MIHON), BrowseGroupTab.Novel).count)
        assertTrue(DesktopSourceFilter().accepts(null, null), "no filter accepts works from removed sources")
        assertFalse(builtIn.accepts(null, "MANGA"), "an uninstalled source has no known origin")
    }

    @Test
    fun `library selection applies the source filter through installed ecosystems`() {
        fun entry(id: Long, source: String, type: String) = DesktopLibraryEntry(SourceContent(id, "作品 $id", emptySet(),
            "/$id", "/$id", 0f, null, null, emptySet(), null, emptySet(), SourceRef(source, "zh", type)),
            emptySet(), id, null, null)
        val snapshot = DesktopLibrarySnapshot(listOf(entry(1, "komiic", "MANGA"), entry(2, "allnovel", "NOVEL"),
            entry(3, "removed", "MANGA")))
        val ecosystems = sources.associate { it.source.name to it.ecosystem }
        fun ids(filter: DesktopSourceFilter) =
            DesktopLibrarySelection(sourceFilter = filter).select(snapshot, ecosystems).map { it.content.id }.sorted()
        assertEquals(listOf(1L, 2L, 3L), ids(DesktopSourceFilter()))
        assertEquals(listOf(2L), ids(DesktopSourceFilter(setOf(SourceTag.TSUNDOKU))))
        assertEquals(listOf(1L, 3L), ids(DesktopSourceFilter(tab = BrowseGroupTab.Content)))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    @Timeout(60)
    fun `top bar filter menus toggle source types and pick a content type`() {
        runDesktopComposeUiTest(width = 600, height = 500) {
            var filter by mutableStateOf(DesktopSourceFilter())
            setContent {
                DesktopTheme {
                    Column {
                        DesktopSourceFilterControls(filter, sources, { filter = it }, enabled = true, tagPrefix = "t")
                        sources.filter(filter::accepts).forEach { Text(it.displayName, Modifier.testTag("row:${it.displayName}")) }
                    }
                }
            }
            onNodeWithTag("t-source-tags").performClick()
            onNodeWithTag("t-source-tag:aniyomi").assertIsNotEnabled()
            onNodeWithTag("t-source-tag:mihon").performClick()
            assertEquals(setOf(SourceTag.MIHON), filter.tags)
            onNodeWithTag("row:mangadex").assertExists()
            onNodeWithTag("row:komiic").assertDoesNotExist()
            onNodeWithTag("t-source-tags").performClick()
            onNodeWithTag("t-source-tag:builtin").performClick()
            assertEquals(setOf(SourceTag.MIHON, SourceTag.BUILTIN), filter.tags)
            onNodeWithTag("t-content-type").performClick()
            onNodeWithTag("t-content-type:video").assertIsNotEnabled()
            onNodeWithTag("t-content-type:manga").performClick()
            assertEquals(BrowseGroupTab.Content, filter.tab)
            onNodeWithText("漫画").assertExists()
            onNodeWithTag("t-source-tags").performClick()
            onNodeWithTag("t-source-tag:all").performClick()
            assertTrue(filter.tags.isEmpty())
            onNodeWithTag("row:allnovel").assertDoesNotExist() // still limited to manga
            onNodeWithTag("row:komiic").assertExists()
        }
    }
}
