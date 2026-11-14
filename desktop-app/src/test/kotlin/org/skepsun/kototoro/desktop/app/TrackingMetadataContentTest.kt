package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.TrackingMetadataContent
import org.skepsun.kototoro.core.ui.compose.TrackingMetadataSlot
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class TrackingMetadataContentTest {
    private val slots = listOf(
        TrackingMetadataSlot("original", "原始内容源", "葬送的芙莉莲", true, isOriginal = true),
        TrackingMetadataSlot("anilist", "AniList", "Frieren: Beyond Journey’s End", false,
            authorized = true, editable = true, communityScore = "9.1", status = 1, rating = .8f, progress = 24),
        TrackingMetadataSlot("mal", "MyAnimeList", null, false),
    )
    private val statuses = listOf("计划阅读", "正在阅读", "重读", "已完成", "搁置", "放弃")

    @Test
    fun `viewing a tracker leaves metadata unchanged until explicitly selected`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var selected by mutableStateOf("original")
            var metadata = "original"
            setContent {
                DesktopTheme {
                    Surface {
                        TrackingMetadataContent(slots, selected, statuses, false, false,
                            onSelectSlot = { selected = it.key }, onUseMetadata = { metadata = it.key },
                            onSearch = {}, onRemove = {}, onSave = { _, _, _, _ -> },
                            modifier = Modifier.fillMaxSize().padding(16.dp))
                    }
                }
            }
            onNodeWithTag("tracking-slot:anilist").performClick()
            runOnIdle { assertEquals("original", metadata) }
            onNodeWithTag("tracking-progress").assertExists()
            onNodeWithTag("tracking-save").assertIsNotEnabled()
            val report = Files.createDirectories(Path.of("build/reports/tracking-metadata"))
                .resolve("compact-380.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("tracking-use-metadata").performClick()
            runOnIdle { assertEquals("anilist", metadata) }
        }

    @Test
    fun `invalid progress and ratings cannot be saved and valid edits preserve status`() =
        runDesktopComposeUiTest(width = 680, height = 800) {
            var saved: Triple<Int, Float, Int?>? = null
            setContent {
                DesktopTheme {
                    Surface {
                        TrackingMetadataContent(slots, "anilist", statuses, false, false,
                            onSelectSlot = {}, onUseMetadata = {}, onSearch = {}, onRemove = {},
                            onSave = { _, progress, rating, status -> saved = Triple(progress, rating, status) },
                            modifier = Modifier.fillMaxSize().padding(20.dp))
                    }
                }
            }
            onNodeWithTag("tracking-progress").performTextReplacement("-1")
            onNodeWithTag("tracking-save").assertIsNotEnabled()
            onNodeWithTag("tracking-progress").performTextReplacement("30")
            onNodeWithTag("tracking-rating").performTextReplacement("11")
            onNodeWithTag("tracking-save").assertIsNotEnabled()
            onNodeWithTag("tracking-rating").performTextReplacement("9.5")
            onNodeWithTag("tracking-save").assertIsEnabled().performClick()
            runOnIdle { assertEquals(Triple(30, .95f, 1), saved) }
        }
}
