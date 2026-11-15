package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import org.jetbrains.skia.Image
import java.nio.file.Files
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's Explore writes down *why* its source section is empty and offers the next step for that reason (§25);
 * Windows showed "0 sources" over a blank area. This probe opens Browse on a fresh runtime (no extension installed),
 * checks that the page says so and that its button reaches the extension manager, then installs one fixture extension,
 * hides it behind a search that matches nothing and checks that the page switches to the "hidden by filters" sentence
 * and that its button brings the grid back.
 */
internal object DesktopBrowseEmptyProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 900) {
                setContent { DesktopApp(controller) }
                fun settled() {
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                // The two states are also kept as screenshots: the sentences are the whole point of the fix.
                fun shot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                        Files.write(Files.createDirectories(Path.of(args[3])).resolve("$name.png"), image.encodeToData()!!.bytes)
                    }
                }
                fun title(): String =
                    onNodeWithTag("source-grid-empty-title").fetchSemanticsNode()
                        .config[SemanticsProperties.Text].joinToString() { it.text }

                settled()
                check(controller.state.value.screen == DesktopScreen.EXPLORE)
                check(controller.state.value.enabledSources.isEmpty()) { "probe root must start without sources" }
                onNodeWithTag("source-grid-empty").assertExists()
                onNodeWithTag("source-grid-empty-title").assertIsDisplayed()
                check(title() == desktopText("sourceGrid.noSourcesTitle")) { "unexpected title: ${title()}" }
                shot("browse-empty-no-sources")
                // No installable source: the one useful next step is the extension manager.
                onNodeWithTag("source-grid-empty-action").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.EXTENSIONS }
                waitForIdle()

                runBlocking { controller.importJar(Path.of(args[2])).join() }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.enabledSources.isNotEmpty() }
                settled()
                onNodeWithTag("source-grid-empty").assertDoesNotExist()
                onNodeWithTag("source-list").assertExists()

                // A search that matches nothing is "hidden by filters": say so and offer the reset, not the manager.
                onNodeWithTag("source-grid-query").performTextInput("zzz-no-such-source")
                waitForIdle()
                onNodeWithTag("source-grid-empty").assertExists()
                check(title() == desktopText("sourceGrid.filteredTitle")) { "unexpected title: ${title()}" }
                shot("browse-empty-filtered")
                onNodeWithTag("source-grid-empty-action").performClick()
                waitForIdle()
                onNodeWithTag("source-grid-empty").assertDoesNotExist()
                onNodeWithTag("source-list").assertExists()
            }
        } finally {
            runBlocking { session.close() }
        }
        println("DESKTOP_UI_OK=browse-empty")
    }
}
