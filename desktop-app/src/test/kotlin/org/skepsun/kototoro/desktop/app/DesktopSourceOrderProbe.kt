package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSort
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's 手动重排 (`SourcesSettingsScreen`'s reorder action over `saveSourceOrder`). Two JSON sources stand in for a
 * library, because the editor needs at least two rows to move; the sequence the dialog saves has to reach the rail, the
 * preference file and the next session. The write phase imports both sites, picks 自定义顺序, moves the second row to
 * the front and checks what that stored; the read phase reopens the data root and checks the rail follows it offline.
 */
internal object DesktopSourceOrderProbe {
    fun run(args: Array<String>) {
        if (args[0] == "sourceorder-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api.php/provide/vod") { exchange ->
                val body = """
                    {"class":[{"type_id":"1","type_name":"电影"}],"list":[]}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(label: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                fun importSource(name: String) {
                    onNodeWithTag("nav:更多").performClick()
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.MORE }
                    idle()
                    onNodeWithTag("nav:扩展与仓库").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.EXTENSIONS }
                    idle()
                    onNodeWithTag("json-source-add").performScrollTo().performClick()
                    idle()
                    onNodeWithTag("json-source-address")
                        .performTextInput("http://127.0.0.1:${server.address.port}/api.php/provide/vod")
                    onNodeWithTag("json-source-name").performTextInput(name)
                    onNodeWithTag("json-source-save").performClick()
                    idle()
                }
                check(controller.state.value.jsonSources.isEmpty())
                idle()
                for (name in listOf(FIRST, SECOND)) importSource(name)
                val stored = controller.state.value.jsonSources
                check(stored.size == 2) { "expected two JSON sources, got $stored" }
                val first = stored.single { it.name == FIRST }.id
                val second = stored.single { it.name == SECOND }.id
                check(controller.state.value.sources.map { it.source.name }.containsAll(listOf(first, second)))
                // The rail follows the installation order until an order is stored.
                check(controller.state.value.sourceSettings.sort == DesktopSourceSort.DEFAULT)
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                // Android only offers the editor while the hand-picked order is the chosen one.
                onNodeWithTag("source-setting-reorder").assertDoesNotExist()
                onNodeWithTag("source-setting-sort").performScrollTo().performClick()
                idle()
                onNodeWithTag("source-settings-dialog").assertExists()
                onNodeWithTag("source-sort:${DesktopSourceSort.CUSTOM.name}").performClick()
                idle()
                onNodeWithTag("source-setting-sort").performScrollTo().assertExists()
                onNodeWithTag("source-setting-reorder").performScrollTo().assertExists()
                snapshot("settings")
                onNodeWithTag("source-setting-reorder").performClick()
                idle()
                onNodeWithTag("source-order-dialog").assertExists()
                onNodeWithTag("source-order:$first").assertExists()
                onNodeWithTag("source-order:$second").assertExists()
                // Nothing is written while the rows move: the dialog is an explicit edit session.
                onNodeWithTag("source-order-up:$second").performClick()
                idle()
                check(controller.state.value.sourceSettings.order.isEmpty()) { "a move wrote the order already" }
                snapshotTag("source-order-dialog", "dialog")
                onNodeWithTag("source-order-save").performClick()
                idle()
                onNodeWithTag("source-order-dialog").assertDoesNotExist()
                val settings = controller.state.value.sourceSettings
                check(settings.sort == DesktopSourceSort.CUSTOM) { "saving the editor did not pick its order" }
                check(settings.order == listOf(second, first)) { "stored order was ${settings.order}" }
                val persisted = (session.storage.preferences.open("desktop_background").snapshot()
                    ["sources_order"] as? SourcePreferenceValue.TextSet)?.values?.toList()
                check(persisted == listOf(second, first)) { "the preference file holds $persisted" }
                // The overview grid draws the same `settings.sorted(...)` sequence the rail does, so the row-major
                // reading order of its two tiles is what "the order the user picked" looks like.
                onNodeWithTag("nav:浏览").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.EXPLORE }
                idle()
                val firstBounds = onNodeWithTag("source:$first").fetchSemanticsNode().boundsInRoot
                val secondBounds = onNodeWithTag("source:$second").fetchSemanticsNode().boundsInRoot
                val rail = controller.state.value.sourceSettings
                    .sorted(controller.state.value.enabledSources).map { it.source.name }
                println("DESKTOP_SOURCE_ORDER rail=$rail first=$firstBounds second=$secondBounds")
                check(rail == listOf(second, first)) { "the sorted sequence is $rail" }
                check(readsBefore(secondBounds, firstBounds)) {
                    "the overview kept $second at $secondBounds after $first at $firstBounds"
                }
                snapshot("overview")
            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            val stored = controller.state.value.jsonSources
            check(stored.size == 2) { "the JSON sources did not survive the restart: $stored" }
            val first = stored.single { it.name == FIRST }.id
            val second = stored.single { it.name == SECOND }.id
            // Android restores the sequence from its per-source sort keys; Windows keeps it in the preference file.
            val settings = controller.state.value.sourceSettings
            check(settings.sort == DesktopSourceSort.CUSTOM) { "the sort order came back as ${settings.sort}" }
            check(settings.order == listOf(second, first)) { "the sequence came back as ${settings.order}" }
            println("DESKTOP_SOURCE_ORDER=${settings.order}")
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:浏览").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.EXPLORE }
                idle()
                val firstBounds = onNodeWithTag("source:$first").fetchSemanticsNode().boundsInRoot
                val secondBounds = onNodeWithTag("source:$second").fetchSemanticsNode().boundsInRoot
                check(readsBefore(secondBounds, firstBounds)) {
                    "the restored overview kept $second at $secondBounds after $first at $firstBounds"
                }
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                onNodeWithTag("source-setting-reorder").performScrollTo().assertExists()
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /** Row-major reading order of two tiles: the one sharing a row ranks by its column. */
    private fun readsBefore(a: Rect, b: Rect): Boolean = if (a.top != b.top) a.top < b.top else a.left < b.left

    private const val FIRST = "重排甲"
    private const val SECOND = "重排乙"
}
