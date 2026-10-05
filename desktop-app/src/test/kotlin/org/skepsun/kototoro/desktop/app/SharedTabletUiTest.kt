package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.preview.*

class SharedTabletUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shared Android preview keeps actions reachable in short Windows windows`() {
        for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            for (height in listOf(640, 300)) runDesktopComposeUiTest(width = 380, height = height) {
                var enabled by mutableStateOf(true)
                val actions = mutableListOf<String>()
                val data = TabletPreviewData(42, "作品标题", "作者", "来源", "简介".repeat(300),
                    (1..10).map { "标签 $it" }, "8.5", "10 章节", "连载中",
                    listOf(TabletPreviewSection("章节", listOf(TabletPreviewChapter(7, "第七章", "#7 · 作者")))))
                setContent {
                    DesktopTheme(appearance) {
                        TabletPreviewSurface {
                            TabletPreviewContent(data, TabletPreviewLabels("关闭", "阅读", "详情", "收藏", "失败", "重试"),
                                cover = { Box(it.background(Color.Blue)) }, icon = { ColorPainter(Color.White) },
                                onClose = { actions.add("close") }, onRead = { actions.add("read") },
                                onOpenDetails = { actions.add("details") }, onAddToFavorites = { actions.add("favourite") },
                                onOpenChapter = { actions.add("chapter:$it") }, controlsEnabled = enabled)
                        }
                    }
                }
                val header = onNodeWithTag("tablet-preview-header").fetchSemanticsNode().boundsInRoot
                assertEquals(if (height < 520) 132f else 200f, header.height)
                onNodeWithTag("details-close").performClick()
                onNodeWithTag("preview-read").performScrollTo().performClick()
                onNodeWithTag("details-expand").performScrollTo().performClick()
                onNodeWithTag("preview-favourite").performScrollTo().performClick()
                onNodeWithTag("preview-chapter:7").performScrollTo().performClick()
                assertEquals(listOf("close", "read", "details", "favourite", "chapter:7"), actions)
                if (appearance == DesktopAppearance.DARK) {
                    val heading = onNodeWithText("章节").performScrollTo().fetchSemanticsNode().boundsInRoot
                    val pixels = onRoot().captureToImage().toPixelMap()
                    assertTrue((heading.top.toInt() until heading.bottom.toInt()).any { y ->
                        (heading.left.toInt() until heading.right.toInt()).any { x ->
                            val pixel = pixels[x, y]
                            pixel.red + pixel.green + pixel.blue > 1.8f
                        }
                    }, "Dark preview chapter heading must contain a readable light foreground")
                }
                runOnIdle { enabled = false }
                onNodeWithTag("preview-read").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("preview-favourite").assertIsNotEnabled()
            }
        }
    }
}
