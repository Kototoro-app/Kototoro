package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.skepsun.kototoro.reader.novel.compose.*
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterPanelHeader
import org.skepsun.kototoro.reader.ui.compose.design.materialReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode
import java.nio.file.Files
import java.nio.file.Path

/** A long grouped directory exercises lazy row locating independently of live source chapter counts. */
internal object DesktopNovelDirectoryProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val narrow = args[0].endsWith("narrow")
        runDesktopComposeUiTest(width = if (narrow) 520 else 1260, height = if (narrow) 620 else 850) {
            val fixture = (0 until 120).map {
                NovelChapterDirectoryEntry(it.toLong(), "Chapter $it", it / 10 + 1, "Group ${it / 30}",
                    listOf("Group ${it / 30}", "Translator ${it / 30}"))
            }
            var chapters by mutableStateOf(fixture)
            var current by mutableIntStateOf(84)
            var locate by mutableIntStateOf(0)
            var enabled by mutableStateOf(true)
            val selections = mutableListOf<Int>()
            val labels = NovelChapterDirectoryLabels("Find chapter", "Clear", "Reverse", "Unnamed")
            val volumeTitle: (Int) -> String = { "Volume $it" }
            setContent {
                MaterialTheme(colorScheme = if (narrow) darkColorScheme() else lightColorScheme()) {
                    ReaderPanelHost(
                        colors = materialReaderPanelColors(MaterialTheme.colorScheme),
                        surfaceMode = ReaderPanelSurfaceMode.Opaque,
                        onDismissRequest = {},
                        openExpanded = true,
                        header = {
                            ReaderChapterPanelHeader("Long novel", "Chapter $current") {
                                NovelChapterLocateButton("Locate current", current in chapters.indices) { locate++ }
                            }
                        },
                    ) { drag ->
                        NovelChapterDirectoryContent(chapters, labels, volumeTitle, current,
                            onChapterSelected = { selections += it; current = it },
                            locateRequest = locate, dragModifier = drag, enabled = enabled,
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            fun row(index: Int) = onNodeWithTag("novel-directory-chapter:$index:$index")
            fun snapshot(label: String) {
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use {
                        val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                        Files.write(output, it.bytes)
                    }
                }
            }
            waitForIdle()
            row(84).assertIsDisplayed().assertIsSelected()
            onNodeWithTag("novel-directory-list").performScrollToIndex(0)
            row(84).assertDoesNotExist()
            onNodeWithTag("novel-directory-locate").performClick(); waitForIdle()
            row(84).assertIsDisplayed().assertIsSelected()
            snapshot("located")
            onNodeWithTag("novel-directory-reverse").performClick(); waitForIdle()
            onNodeWithTag("novel-directory-reverse").assertIsSelected()
            row(84).assertIsDisplayed().assertIsSelected()
            row(83).assertIsDisplayed()
            check(row(84).fetchSemanticsNode().boundsInRoot.top < row(83).fetchSemanticsNode().boundsInRoot.top)
            onNodeWithTag("novel-search-query").performTextInput("unknown")
            row(84).assertDoesNotExist()
            onNodeWithTag("novel-directory-locate").performClick(); waitForIdle()
            onNodeWithTag("novel-search-query").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            row(84).assertIsDisplayed().assertIsSelected()
            onNodeWithTag("novel-search-query").performTextInput("CHAPTER 7")
            row(84).assertDoesNotExist()
            row(79).assertIsDisplayed()
            runOnIdle { enabled = false }
            row(79).assertIsNotEnabled().performClick()
            check(selections.isEmpty())
            runOnIdle { enabled = true }
            row(79).performClick(); waitForIdle()
            check(selections == listOf(79) && current == 79)
            row(79).assertIsSelected()
            onNodeWithTag("novel-search-clear").performClick(); waitForIdle()
            row(79).assertIsDisplayed().assertIsSelected()
            onNodeWithTag("novel-search-query").performTextInput("TRANSLATOR 3")
            row(79).assertDoesNotExist()
            row(119).assertIsDisplayed()
            onNodeWithTag("novel-directory-locate").performClick(); waitForIdle()
            row(79).assertIsDisplayed().assertIsSelected()
            snapshot("selected")
            runOnIdle { chapters = emptyList() }
            onNodeWithTag("novel-directory-locate").assertIsNotEnabled()
            row(79).assertDoesNotExist()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
