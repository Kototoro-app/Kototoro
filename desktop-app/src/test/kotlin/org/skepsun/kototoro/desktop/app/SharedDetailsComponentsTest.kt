package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.ui.chapters.VideoEpisodePreviewItem
import org.skepsun.kototoro.core.ui.chapters.VideoEpisodePreviewSection
import org.skepsun.kototoro.core.ui.chapters.VideoLineOption
import org.skepsun.kototoro.core.ui.compose.DetailsMediaStyle
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataEntry
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataGrid
import org.skepsun.kototoro.core.ui.compose.ExpandableDescriptionText
import org.skepsun.kototoro.core.ui.compose.NovelBookCoverFrame
import org.skepsun.kototoro.core.ui.compose.NovelCatalogEntryRow
import org.skepsun.kototoro.core.ui.compose.NovelEditorialSynopsisBody
import org.skepsun.kototoro.core.ui.compose.NovelHeroHeaderContent
import org.skepsun.kototoro.core.ui.compose.NovelMetricItem
import org.skepsun.kototoro.core.ui.compose.NovelMetricsStrip
import org.skepsun.kototoro.core.ui.compose.VideoHeroHeaderContent
import org.skepsun.kototoro.core.ui.compose.detailsMediaStylePolicy
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

@OptIn(ExperimentalTestApi::class)
class SharedDetailsComponentsTest {
    @Test
    fun `cinema supplementary facts omit status and alias already shown in hero`() =
        runDesktopComposeUiTest(width = 420, height = 800) {
            val content = SourceContent(
                id = 7, title = "Cinema", altTitles = linkedSetOf("Hero alias", "Extra alias"),
                url = "/work", publicUrl = "/work", rating = 0.8f, contentRating = null,
                coverUrl = null, tags = emptySet(), state = "FINISHED", authors = emptySet(),
                source = SourceRef("fixture", "en", "VIDEO"), chapters = null,
            )
            var cinema by mutableStateOf(true)
            setContent {
                DesktopTheme {
                    DesktopDetailsInfo(content, enabled = true, heroVisible = cinema)
                }
            }
            onNodeWithTag("details-status").assertDoesNotExist()
            onNodeWithText("Hero alias", substring = true).assertDoesNotExist()
            onNodeWithText("Extra alias").assertExists()
            onNodeWithTag("details-rating").assertExists()
            onNodeWithTag("details-copy-link").assertExists()
            runOnIdle { cinema = false }
            onNodeWithTag("details-status").assertExists()
            onNodeWithText("Hero alias", substring = true).assertExists()
        }

    @Test
    fun `a long description toggles on click and a short one stays plain text`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var expanded by mutableStateOf(false)
            setContent {
                DesktopTheme {
                    Surface {
                        Column(Modifier.width(300.dp)) {
                            ExpandableDescriptionText("Line. ".repeat(200), expanded, { expanded = it },
                                modifier = Modifier.testTag("long"))
                            ExpandableDescriptionText("Short.", false, { error("short text must not toggle") },
                                modifier = Modifier.testTag("short"))
                        }
                    }
                }
            }
            val collapsed = onNodeWithTag("long").fetchSemanticsNode().size.height
            onNodeWithTag("short").assertHasNoClickAction()
            onNodeWithTag("long").assertHasClickAction().performClick()
            runOnIdle { assertTrue(expanded) }
            waitForIdle()
            assertTrue(onNodeWithTag("long").fetchSemanticsNode().size.height > collapsed * 2) { "unfolds the text" }
            onNodeWithTag("long").performClick()
            runOnIdle { assertFalse(expanded) }
        }

    @Test
    fun `metadata facts name their field for screen readers`() =
        runDesktopComposeUiTest(width = 380, height = 300) {
            setContent {
                DesktopTheme {
                    Surface {
                        DetailsMetadataGrid(listOf(
                            DetailsMetadataEntry("State", "Ongoing", rememberVectorPainter(ReaderPanelIcons.Timer)),
                            DetailsMetadataEntry("Rating", "5.0", null),
                            DetailsMetadataEntry("Alias", "Other", rememberVectorPainter(ReaderPanelIcons.Search)),
                        ))
                    }
                }
            }
            onNodeWithContentDescription("State").assertExists()
            onNodeWithContentDescription("Rating").assertExists()
            onNodeWithText("Ongoing").assertExists()
            onNodeWithText("Other").assertExists()
        }

    @Test
    fun `video hero header triggers cover chapter and primary play callbacks`() =
        runDesktopComposeUiTest(width = 420, height = 640) {
            var coverTapped = false
            var playTapped = false
            var chaptersTapped = false
            setContent {
                DesktopTheme {
                    Surface {
                        VideoHeroHeaderContent(
                            title = "Frieren",
                            alternateTitlesText = "葬送的芙莉莲",
                            metadataSummary = "已完结 · 28/28 · Madhouse",
                            progressPercent = 0.5f,
                            onCoverClick = { coverTapped = true },
                            onMetadataClick = { chaptersTapped = true },
                            primaryPlayAction = {
                                Button(onClick = { playTapped = true }, modifier = Modifier.testTag("play-btn")) {
                                    Text("Play")
                                }
                            },
                        )
                    }
                }
            }
            onNodeWithText("Frieren").assertExists()
            onNodeWithText("已完结 · 28/28 · Madhouse").assertExists()
            onNodeWithText("50%").assertExists()
            onNodeWithTag("video-hero-cover-tap").performClick()
            onNodeWithTag("play-btn").performClick()
            onNodeWithTag("video-hero-metadata-line").performClick()
            runOnIdle {
                assertTrue(coverTapped)
                assertTrue(playTapped)
                assertTrue(chaptersTapped)
            }
        }

    @Test
    fun `video episode preview switches between grid chips and list rows and handles selection`() =
        runDesktopComposeUiTest(width = 420, height = 640) {
            var clickedId by mutableStateOf<Long?>(null)
            var selectedLine by mutableStateOf<String?>("Line A")
            var showAllClicked by mutableStateOf(false)
            val shortEpisodes = (1L..10L).map {
                VideoEpisodePreviewItem(id = it, title = "第${it}集", isCurrent = it == 2L)
            }
            setContent {
                DesktopTheme {
                    Surface {
                        VideoEpisodePreviewSection(
                            episodes = shortEpisodes,
                            totalCount = 10,
                            lines = listOf(
                                VideoLineOption("Line A", "Line A", selectedLine == "Line A"),
                                VideoLineOption("Line B", "Line B", selectedLine == "Line B"),
                            ),
                            onSelectLine = { selectedLine = it },
                            onShowAllClick = { showAllClicked = true },
                            onEpisodeClick = { clickedId = it.id },
                        )
                    }
                }
            }
            onNodeWithTag("video-episode-chip:2").assertExists().performClick()
            onNodeWithTag("video-line:Line B").performClick()
            onNodeWithTag("video-episode-show-all").performClick()
            runOnIdle {
                assertEquals(2L, clickedId)
                assertEquals("Line B", selectedLine)
                assertTrue(showAllClicked)
            }
        }

    @Test
    fun `details media style policies keep bookmarks tab enabled for video and differentiate novel`() {
        val defaultVideoPolicy = detailsMediaStylePolicy("VIDEO")
        val defaultNovelPolicy = detailsMediaStylePolicy("NOVEL")
        val defaultMangaPolicy = detailsMediaStylePolicy("MANGA")

        assertEquals(DetailsMediaStyle.MANGA, defaultVideoPolicy.style)
        assertFalse(defaultVideoPolicy.showPagesTab)
        assertTrue(defaultVideoPolicy.showBookmarksTab)

        assertEquals(DetailsMediaStyle.MANGA, defaultNovelPolicy.style)
        assertFalse(defaultNovelPolicy.showPagesTab)
        assertTrue(defaultNovelPolicy.showBookmarksTab)

        assertEquals(DetailsMediaStyle.MANGA, defaultMangaPolicy.style)
        assertTrue(defaultMangaPolicy.showPagesTab)
        assertTrue(defaultMangaPolicy.showBookmarksTab)

        val videoPolicy = detailsMediaStylePolicy("VIDEO", DetailsMediaStyle.VIDEO)
        val novelPolicy = detailsMediaStylePolicy("NOVEL", DetailsMediaStyle.NOVEL)
        val mangaPolicy = detailsMediaStylePolicy("MANGA", DetailsMediaStyle.MANGA)

        assertEquals(DetailsMediaStyle.VIDEO, videoPolicy.style)
        assertTrue(videoPolicy.showBookmarksTab)
        assertTrue(videoPolicy.useVideoHeroBackdrop)
        assertTrue(videoPolicy.useCenteredHeroHeader)

        assertEquals(DetailsMediaStyle.NOVEL, novelPolicy.style)
        assertTrue(novelPolicy.showBookmarksTab)
        assertFalse(novelPolicy.useVideoHeroBackdrop)

        assertEquals(DetailsMediaStyle.MANGA, mangaPolicy.style)
        assertTrue(mangaPolicy.showBookmarksTab)
    }

    @Test
    fun `novel hero header and editorial synopsis render book attribution and expand`() =
        runDesktopComposeUiTest(width = 420, height = 640) {
            var authorClicked by mutableStateOf(false)
            var synopsisExpanded by mutableStateOf(false)
            setContent {
                DesktopTheme {
                    Surface {
                        Column(Modifier.width(360.dp)) {
                            NovelHeroHeaderContent(
                                title = "Ascendance of a Bookworm",
                                authorText = "Miya Kazuki",
                                alternateTitlesText = "本好きの下剋上",
                                bookMetadataLine = "Ongoing · 33 Volumes",
                                onAuthorClick = { authorClicked = true },
                                coverSlot = {
                                    NovelBookCoverFrame {
                                        Text("Cover")
                                    }
                                },
                                titleExtraSlot = {
                                    Text("Ascendance of a Bookworm")
                                },
                            )
                            NovelEditorialSynopsisBody(
                                text = "A college student who loves books dies in an accident and is reincarnated in another world where books are scarce.",
                                expanded = synopsisExpanded,
                                onExpandedChange = { synopsisExpanded = it },
                                collapsedMaxLines = 1,
                            )
                            NovelMetricsStrip(
                                items = listOf(
                                    NovelMetricItem(label = "章节", value = "128", subtitle = "已完结"),
                                    NovelMetricItem(label = "我的阅读", value = "42%"),
                                ),
                            )
                            NovelCatalogEntryRow(
                                title = "查看目录",
                                subtitle = "128 章",
                                onClick = {},
                            )
                        }
                    }
                }
            }
            onNodeWithTag("novel-hero-header").assertExists()
            onNodeWithTag("novel-hero-author").assertExists().performClick()
            onNodeWithTag("novel-metrics-strip").assertExists()
            onNodeWithTag("novel-catalog-entry").assertExists()
            onNodeWithText("Ongoing · 33 Volumes").assertExists()
            runOnIdle {
                assertTrue(authorClicked)
            }
        }
}
