package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.home.*
import java.nio.file.Files
import java.nio.file.Path

class SharedHomeScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `hero pager uses Android card geometry and safely handles refreshed entries`() {
        runDesktopComposeUiTest(width = 1100, height = 240) {
            var entries by mutableStateOf(listOf("resume:1", "history:2", "update:3"))
            lateinit var pager: PagerState
            var clicked: String? = null
            setContent {
                DesktopTheme {
                    pager = rememberPagerState(pageCount = { entries.size })
                    HomeHeroPager(entries, { it }, pager, TabletLayoutClass.EXPANDED,
                        modifier = Modifier.testTag("test-pager")) { _, entry, modifier ->
                        HomeHeroCardSurface(onClick = { clicked = entry }, modifier = modifier.testTag("hero:$entry"),
                            background = { Box(Modifier.fillMaxSize().background(Color.Blue)) }) { Text(entry) }
                    }
                }
            }
            val bounds = onNodeWithTag("hero:resume:1").fetchSemanticsNode().boundsInRoot
            assertEquals(535f, bounds.width, 1f)
            assertEquals(184f, bounds.height, 1f)
            onNodeWithTag("hero:resume:1").performClick()
            assertEquals("resume:1", clicked)
            runOnIdle { runBlocking { pager.scrollToPage(2) } }
            onNodeWithTag("hero:update:3").performClick()
            assertEquals("update:3", clicked)
            runOnIdle { entries = listOf("history:2") }
            waitForIdle()
            onNodeWithTag("hero:history:2").assertIsDisplayed().performClick()
            assertEquals("history:2", clicked)
            runOnIdle { entries = emptyList() }
            onNodeWithTag("test-pager").assertDoesNotExist()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `home uses the content pane breakpoint centres wide content and keeps quick actions reachable`() {
        for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            for (width in listOf(600, 1079, 1080, 1800)) runDesktopComposeUiTest(width = width, height = 520) {
                var layoutClass: TabletLayoutClass? = null
                var quickExpanded = false
                var clicks = 0
                setContent {
                    DesktopTheme(appearance) {
                        Row(Modifier.fillMaxSize()) {
                            // Both hosts place navigation outside the home pane.
                            Spacer(Modifier.width(80.dp).fillMaxHeight())
                            HomeScreenLayout(
                                hasHero = true, hasHighlights = true, initialized = true, tabletLayoutEnabled = true,
                                modifier = Modifier.weight(1f),
                                hero = { modifier, resolved ->
                                    layoutClass = resolved
                                    HomeHeroCardSurface(onClick = {}, modifier = modifier.testTag("test-hero"),
                                        background = { Box(Modifier.fillMaxSize().background(Color.Blue)) }) {
                                        HomeHeroStandardContent(
                                            poster = { w, h -> Box(Modifier.size(w, h).background(Color.Red)) },
                                            text = { Text("共同主页", it) },
                                        )
                                    }
                                },
                                highlights = {
                                    Column(Modifier.fillMaxWidth().height(900.dp).testTag("test-highlights")) {
                                        Text("历史与推荐")
                                    }
                                },
                                quickActions = { expanded ->
                                    quickExpanded = expanded
                                    Button({ clicks++ }, Modifier.fillMaxWidth().testTag("test-quick")) { Text("打开收藏") }
                                },
                            )
                        }
                    }
                }
                waitForIdle()
                val expanded = width >= 1080
                assertEquals(when {
                    expanded -> TabletLayoutClass.EXPANDED
                    width >= 680 -> TabletLayoutClass.MEDIUM
                    else -> TabletLayoutClass.COMPACT
                }, layoutClass)
                val heroBounds = onNodeWithTag("test-hero").fetchSemanticsNode().boundsInRoot
                if (width == 1800) {
                    assertEquals(1224f, heroBounds.width, 1f)
                    assertEquals((width + 80f) / 2f, heroBounds.center.x, 1f)
                }
                if (expanded) {
                    val main = onNodeWithTag("test-highlights").fetchSemanticsNode().boundsInRoot
                    val quick = onNodeWithTag("test-quick").fetchSemanticsNode().boundsInRoot
                    assertTrue(quick.left > main.right)
                    assertEquals(0.62f / 0.38f, main.width / quick.width, 0.02f)
                } else onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("test-quick"))
                assertEquals(expanded, quickExpanded)
                onNodeWithTag("test-quick").assertIsDisplayed().performClick()
                assertEquals(1, clicks)
                if (expanded && width == 1080) {
                    val target = Path.of("build/reports/desktop-smoke/shared-home-${appearance.name.lowercase()}.png")
                    Files.createDirectories(target.parent)
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(target, it.bytes) }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `home hero layout bounds follow scroll and changing hero height without duplicating quick access`() {
        runDesktopComposeUiTest(width = 1100, height = 520) {
            lateinit var list: LazyListState
            var tall by mutableStateOf(false)
            var hasHero by mutableStateOf(true)
            var heroTop = 0f
            setContent {
                DesktopTheme {
                    list = rememberLazyListState()
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(56.dp).background(Color.Green).testTag("test-header"))
                        HomeScreenLayout(
                            hasHero = hasHero, hasHighlights = true, initialized = true, tabletLayoutEnabled = true,
                            modifier = Modifier.weight(1f),
                            listState = list,
                            hero = { modifier, _ ->
                                Box(modifier.fillMaxWidth().height(if (tall) 240.dp else 184.dp)
                                    .background(Color.Red).onGloballyPositioned { heroTop = it.positionInRoot().y }
                                    .testTag("test-hero"))
                            },
                            highlights = { Box(Modifier.fillMaxWidth().height(900.dp).testTag("test-highlights")) },
                            quickActions = { Text("快捷入口", Modifier.testTag("test-quick")) },
                        )
                    }
                }
            }
            waitForIdle()
            val originalTop = heroTop
            runOnIdle { runBlocking { list.scrollToItem(0, 80) } }
            waitForIdle()
            assertEquals(originalTop - 80f, heroTop, 1f)
            assertEquals(56f, onNodeWithTag("test-hero").fetchSemanticsNode().boundsInRoot.top, 1f)
            val headerPixel = onRoot().captureToImage().toPixelMap()[20, 20]
            assertTrue(headerPixel.green > .99f && headerPixel.red < .01f && headerPixel.blue < .01f,
                "Scrolled hero must never paint over the host's toolbar")
            runOnIdle { tall = true }
            waitForIdle()
            assertEquals(56f + 244f - 80f,
                onNodeWithTag("test-highlights").fetchSemanticsNode().boundsInRoot.top, 1f)
            onAllNodesWithTag("test-quick").assertCountEquals(1)
            runOnIdle { hasHero = false }
            onNodeWithTag("test-hero").assertDoesNotExist()
            onAllNodesWithTag("test-quick").assertCountEquals(1)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `home TV inline hero and empty loading state retain lazy scrolling`() {
        runDesktopComposeUiTest(width = 600, height = 360) {
            var initialized by mutableStateOf(false)
            var hasHighlights by mutableStateOf(false)
            setContent {
                DesktopTheme {
                    HomeScreenLayout(
                        hasHero = hasHighlights, hasHighlights = hasHighlights, initialized = initialized,
                        tabletLayoutEnabled = false, heroInsideList = true, contentPadding = PaddingValues(0.dp),
                        hero = { modifier, _ -> Text("TV Hero", modifier.height(184.dp).testTag("test-hero")) },
                        highlights = { Box(Modifier.height(700.dp)) },
                        quickActions = { Text("快捷入口", Modifier.testTag("test-quick")) },
                        loading = { Text("正在加载", Modifier.testTag("test-loading")) },
                    )
                }
            }
            onNodeWithTag("test-loading").assertExists()
            runOnIdle { initialized = true }
            onNodeWithTag("test-loading").assertDoesNotExist()
            runOnIdle { hasHighlights = true }
            // Existing quick-access keys keep the user's scroll anchor when loaded sections are inserted above it.
            onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("test-hero"))
            onNodeWithTag("test-hero").assertIsDisplayed()
            onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("test-quick"))
            onNodeWithTag("test-quick").assertIsDisplayed()
        }
    }
}
