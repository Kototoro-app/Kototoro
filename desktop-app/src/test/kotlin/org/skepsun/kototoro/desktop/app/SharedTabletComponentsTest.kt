package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.glass.*
import org.skepsun.kototoro.core.ui.source.TabletSourceTile
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.*
import java.nio.file.Files
import java.nio.file.Path

class SharedTabletComponentsTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Android source tiles keep click long press and disabled semantics on desktop`() {
        runDesktopComposeUiTest(width = 400, height = 240) {
            var enabled by mutableStateOf(true)
            val actions = mutableListOf<String>()
            setContent {
                DesktopTheme {
                    TabletSourceTile("共享内容源", 134.dp, 88.dp, 72.dp, 14.sp,
                        modifier = Modifier.width(108.dp).testTag("source"), enabled = enabled,
                        onClick = { actions.add("click") }, onLongClick = { actions.add("long") },
                        icon = { Box(it.background(Color.Blue)) })
                }
            }
            onNodeWithTag("source").performClick()
            onNodeWithTag("source").performTouchInput { longClick() }
            assertEquals(listOf("click", "long"), actions)
            runOnIdle { enabled = false }
            onNodeWithTag("source").assertIsNotEnabled()
            onNodeWithTag("source").performClick()
            assertEquals(2, actions.size)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Android poster title stays on the cover in both desktop themes`() {
        for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = 1260, height = 740) {
                setContent {
                    DesktopTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(Canvas).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("内容源", fontSize = 22.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                listOf("Komic", "Rawkuma", "再漫画", "MangaDex", "Comick", "本地").forEach { name ->
                                    TabletSourceTile(name, 134.dp, 88.dp, 72.dp, 14.sp, onClick = {},
                                        modifier = Modifier.width(108.dp), icon = {
                                            Box(it, contentAlignment = Alignment.Center) { Text(name.take(1), fontSize = 48.sp) }
                                        })
                                }
                            }
                            Text("收藏", fontSize = 22.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(8) { index ->
                                    val style = compactPosterCardStyle(1.15f)
                                    TabletPosterCover("共享作品 $index · A long title on the cover", style,
                                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .3f), Color.Transparent)),
                                        modifier = Modifier.width(style.itemWidth).height(style.posterHeight)
                                            .testTag("poster:$index"),
                                        cover = {
                                            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(
                                                Color(0xFF3D6791), Color(0xFFAF7D96), Color(0xFF97BDAD)))))
                                        },
                                        overlays = {
                                            Text("ZH", color = Color.White, modifier = Modifier.align(Alignment.BottomStart)
                                                .padding(start = 8.dp, bottom = 44.dp))
                                        })
                                }
                            }
                        }
                    }
                }
                repeat(8) { index ->
                    val cover = onNodeWithTag("poster:$index").fetchSemanticsNode().boundsInRoot
                    val title = onNodeWithText("共享作品 $index · A long title on the cover")
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue(title.left >= cover.left && title.right <= cover.right)
                    assertTrue(title.top >= cover.top && title.bottom <= cover.bottom)
                }
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use {
                        val directory = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                        Files.write(directory.resolve("shared-components-${appearance.name.lowercase()}.png"), it.bytes)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Android card badges and progress bar draw the same colours and fill on desktop`() {
        for (style in DesktopInterfaceStyle.entries) {
            runDesktopComposeUiTest(width = 200, height = 120) {
                var primary = Color.Unspecified
                setContent {
                    DesktopTheme(DesktopAppearance.LIGHT, style) {
                        primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        val metrics = contentCardBadgeMetricsFor(112.dp)
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            Box(Modifier.width(200.dp).height(4.dp)) {
                                ContentCardBottomProgressBar(.5f, completed = false)
                            }
                            Box(Modifier.width(200.dp).height(4.dp)) {
                                ContentCardBottomProgressBar(1f, ReadingProgress.isCompleted(1f))
                            }
                            Box(Modifier.width(200.dp).height(4.dp).testTag("zero")) {
                                ContentCardBottomProgressBar(0f, completed = false)
                            }
                            ContentCardBadgePill(ContentCardBadgeTone.COUNTER, style == DesktopInterfaceStyle.IOS,
                                metrics, Modifier.padding(top = 20.dp).testTag("counter")) { colors ->
                                ContentCardBadgeText("17", colors.content, metrics)
                            }
                        }
                    }
                }
                val pixels = onRoot().captureToImage().toPixelMap()
                fun near(actual: Color, expected: Color) = kotlin.math.abs(actual.red - expected.red) < .06f &&
                    kotlin.math.abs(actual.green - expected.green) < .06f && kotlin.math.abs(actual.blue - expected.blue) < .06f
                assertTrue(near(pixels[50, 1], primary), "Half progress fills the start with the theme primary")
                assertTrue(pixels[150, 1].luminance() < .6f, "The unread half stays on the dark track")
                assertTrue(near(pixels[190, 5], Color(0xFF34C759)), "Completed progress turns green")
                assertTrue(pixels[100, 9].luminance() > .95f, "No progress draws no bar")
                val counter = onNodeWithTag("counter").fetchSemanticsNode().boundsInRoot
                val fill = pixels[(counter.left + 3).toInt(), counter.center.y.toInt()]
                val expected = if (style == DesktopInterfaceStyle.IOS) Color(0xFFFF3B30) else primary
                assertTrue(near(fill.compositeOver(Color.White), expected.copy(alpha = .92f).compositeOver(Color.White)),
                    "$style counter pill uses Android's accent")
                onNodeWithText("17").assertExists()
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `details chapters are grouped by branch and volume like Android`() {
        val source = org.skepsun.kototoro.core.source.SourceRef("fixture", "zh", "MANGA")
        fun chapter(id: Long, branch: String?, volume: Int, scanlator: String? = null) =
            org.skepsun.kototoro.core.source.SourceChapter(id, "第 $id 话", id.toFloat(), volume, "/$id", scanlator,
                0, branch, source)
        val chapters = listOf(chapter(1, "汉化组", 1), chapter(2, "汉化组", 1), chapter(3, "汉化组", 2),
            chapter(11, "English", 0, "Season 1"), chapter(12, "English", 0, "Season 1"))

        val chinese = desktopChapterList(chapters, "汉化组", "", false)
        assertEquals(setOf("English", "汉化组"), chinese.options.map { it.name }.toSet())
        assertEquals("汉化组", chinese.selectedBranch)
        assertEquals(3, chinese.count)
        assertEquals(listOf("V1", 1L, 2L, "V2", 3L), chinese.sections.map {
            when (it) {
                is org.skepsun.kototoro.core.ui.chapters.ChapterSection.Header -> "V${it.volume}"
                is org.skepsun.kototoro.core.ui.chapters.ChapterSection.Item -> it.chapter.id
            }
        })
        val reversed = desktopChapterList(chapters, "汉化组", "", true)
        assertEquals(listOf(3L, 2L, 1L), reversed.sections.mapNotNull {
            (it as? org.skepsun.kototoro.core.ui.chapters.ChapterSection.Item)?.chapter?.id
        })
        val searched = desktopChapterList(chapters, "汉化组", "第 2", false)
        assertEquals(2, searched.sections.size, "search keeps the hit and its volume header")
        // A branch that disappeared after a refresh falls back to the largest branch.
        assertEquals("汉化组", desktopChapterList(chapters, "gone", "", false).selectedBranch)
        assertNull(desktopChapterList(chapters.take(3), "汉化组", "", false).selectedBranch, "one branch shows no chips")

        runDesktopComposeUiTest(width = 600, height = 300) {
            var selected by mutableStateOf<String?>("汉化组")
            setContent {
                DesktopTheme {
                    val list = desktopChapterList(chapters, selected, "", false)
                    Column {
                        org.skepsun.kototoro.core.ui.chapters.ChapterBranchChips(list.options, list.selectedBranch,
                            title = { it ?: "默认" }, onSelect = { selected = it },
                            chipModifier = { Modifier.testTag("branch:${it.name}") })
                        list.sections.filterIsInstance<org.skepsun.kototoro.core.ui.chapters.ChapterSection.Header>()
                            .forEach { org.skepsun.kototoro.core.ui.chapters.ChapterSectionHeader(it.customName ?: "第 ${it.volume} 卷") }
                    }
                }
            }
            onNodeWithText("汉化组 · 3").assertIsSelected()
            onNodeWithText("第 2 卷").assertExists()
            onNodeWithTag("branch:English").performClick()
            assertEquals("English", selected)
            onNodeWithText("English · 2").assertIsSelected()
            onNodeWithText("Season 1").assertExists()
            onNodeWithText("第 2 卷").assertDoesNotExist()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    @org.junit.jupiter.api.Timeout(60)
    fun `Android top bar rails select tabs and filters on desktop`() {
        runDesktopComposeUiTest(width = 700, height = 260) {
            val tabs = listOf(org.skepsun.kototoro.core.ui.topbar.TopBarTabItem(1, "稍后阅读")) +
                (2L..30L).map { org.skepsun.kototoro.core.ui.topbar.TopBarTabItem(it, "分类 $it") }
            var selectedTab by mutableStateOf(1L)
            var filters by mutableStateOf(setOf("本地"))
            setContent {
                DesktopTheme {
                    val surface: org.skepsun.kototoro.core.ui.topbar.RailSurface = { modifier ->
                        DesktopControlSurface(modifier.testTag("rail-surface"), RoundedCornerShape(percent = 50)) {}
                    }
                    Column {
                        org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock("收藏", subtitle = "158 部作品")
                        org.skepsun.kototoro.core.ui.topbar.TopBarTabsRail(tabs, selectedTab, { selectedTab = it }, surface,
                            Modifier.fillMaxWidth(), itemModifier = { Modifier.testTag("tab:${it.id}") })
                        org.skepsun.kototoro.core.ui.topbar.TopBarFilterRail(listOf("本地", "新章节", "已完结"),
                            key = { it }, title = { it }, isSelected = { it in filters },
                            onClick = { filters = if (it in filters) filters - it else filters + it }, surface = surface,
                            leading = { item, _ -> Text("•", modifier = Modifier.testTag("leading:$item")) },
                            itemModifier = { Modifier.testTag("filter:$it") })
                    }
                }
            }
            onNodeWithText("158 部作品").assertExists()
            assertEquals(2, onAllNodesWithTag("rail-surface").fetchSemanticsNodes().size)
            onNodeWithTag("tab:2").performClick()
            assertEquals(2L, selectedTab)
            // Selecting a far tab scrolls the rail to it.
            runOnIdle { selectedTab = 30L }
            waitForIdle()
            onNodeWithTag("tab:30").assertIsDisplayed()
            onNodeWithTag("leading:新章节", useUnmergedTree = true).assertExists()
            onNodeWithTag("filter:新章节").performClick()
            assertEquals(setOf("本地", "新章节"), filters)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shared Android glass actually blurs the Skiko backdrop`() {
        for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = 400, height = 300) {
                setContent {
                    DesktopTheme(appearance) {
                        val backdrop = rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                for (x in 0 until size.width.toInt() step 4) drawRect(
                                    if (x % 8 == 0) Color.Red else Color.Blue,
                                    topLeft = Offset(x.toFloat(), 0f), size = Size(4f, size.height))
                            }
                            SharedLiquidGlassSurface(backdrop, emptyGlassTuningState(),
                                GlassStyle(.82f, .24f, 0.dp, 4.dp), RoundedCornerShape(28.dp),
                                GlassComponentRole.PillControl,
                                modifier = Modifier.align(Alignment.Center).size(160.dp, 100.dp).testTag("glass")) {}
                        }
                    }
                }
                val pixels = onRoot().captureToImage().toPixelMap()
                fun variation(y: Int): Float = (175 until 225).sumOf {
                    kotlin.math.abs(pixels[it, y].red - pixels[it + 1, y].red).toDouble()
                }.toFloat()
                assertTrue(variation(150) < variation(20) * .2f, "Glass must smooth the source stripes")
                val color = pixels[200, 150]
                assertTrue(color.red > .05f && color.blue > .05f, "Glass must sample the backdrop colours")
            }
        }
    }
}
