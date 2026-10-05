package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** Real rendered themes, authored cover requests and disk restart; isolated from the user's library. */
internal object DesktopAppearanceProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val writing = args[0] == "appearance-write"
        System.setProperty(if (writing) "fixture.cover.repaired" else "fixture.cover.offline", "yes")
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (writing) runBlocking {
                controller.importJar(Path.of(args[7])).join()
                controller.appearance(DesktopAppearance.LIGHT).join()
            } else {
                check(controller.state.value.appearance == DesktopAppearance.DARK)
                check(controller.state.value.interfaceStyle == DesktopInterfaceStyle.IOS)
            }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(label: String, dark: Boolean) {
                    val bitmap = onRoot().captureToImage().asSkiaBitmap()
                    val colour = java.awt.Color(bitmap.getColor(1200, 840), true)
                    if (dark) check(colour.red < 64 && colour.green < 64 && colour.blue < 64)
                    else check(colour.red > 230 && colour.green > 230 && colour.blue > 230)
                    Image.makeFromBitmap(bitmap).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png"), it.bytes)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }; idle()
                if (writing) {
                    onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick(); idle()
                    val items = controller.state.value.items
                    check(items.size == 3)
                    waitUntil(timeoutMillis = 15_000) {
                        items.all { onAllNodesWithTag("cover:${it.id}", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
                    }
                    snapshot("light-browse", dark = false)
                    onNodeWithTag("content:${items.first().id}").performClick(); idle()
                    onNodeWithTag("details-preview").assertExists()
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("cover:${controller.state.value.content!!.id}", useUnmergedTree = true).fetchSemanticsNodes().size >= 3
                    }
                    snapshot("light-preview", dark = false)
                    onNodeWithTag("preview-favourite").performClick(); idle()
                    onNodeWithTag("details-close").performClick(); idle()
                    check(System.getProperty("fixture.cover.requests./primary.png") == "1")
                    onNodeWithTag("nav:收藏").performClick(); idle()
                    snapshot("light-library", dark = false)
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("appearance:DARK").performClick(); idle()
                    check(controller.state.value.appearance == DesktopAppearance.DARK)
                    snapshot("dark-settings", dark = true)
                }
                onNodeWithTag("nav:收藏").performClick(); idle()
                val content = controller.state.value.library.entries.single().content
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("cover:${content.id}", useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
                snapshot("dark-library", dark = true)
                if (writing) {
                    onNodeWithTag("content:${content.id}").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("cover:${controller.state.value.content!!.id}", useUnmergedTree = true).fetchSemanticsNodes().size >= 3
                    }
                    snapshot("dark-preview", dark = true)
                    onNodeWithTag("details-close").performClick(); idle()
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("interface-style:IOS").performClick(); idle()
                    check(controller.state.value.interfaceStyle == DesktopInterfaceStyle.IOS)
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source-query").assertExists()
                    snapshot("dark-glass-browse", dark = true)
                } else {
                    check(System.getProperty("fixture.cover.requests./primary.png") == null)
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("appearance:LIGHT").performClick(); idle()
                    onNodeWithTag("interface-style:MATERIAL").performClick(); idle()
                    onNodeWithTag("nav:收藏").performClick(); idle()
                    snapshot("restored-light", dark = false)
                }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
