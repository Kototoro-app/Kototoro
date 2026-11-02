package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.parserhost.ParserPluginRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's `jar_priority_order` over the real UI and through two JVMs, because the parser host ranks its plugins once
 * when the session opens. The write phase installs the two parser fixtures that both declare `FIXTURE_SHARED`, moves the
 * second plugin to the front on the source settings page and checks Android's comma separated value; the read phase
 * opens the same data root and checks the shared source actually changed hands.
 */
internal object DesktopJarPriorityProbe {
    fun run(args: Array<String>) {
        if (args[0] == "jarpriority-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val fixtures = Path.of(args[9])
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            for (name in listOf("kototoro", "kotatsu")) {
                runBlocking { controller.importJar(fixtures.resolve("fixture-$name.jar")).join() }
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            }
            val plugins = controller.state.value.installedEntries.filter { it.kind == DesktopInstalledKind.PARSER }
            check(plugins.size == 2) { "expected the two parser fixtures, got $plugins" }
            // Nothing is stored yet, so the registry default decides: neither fixture id is one of Android's plugin
            // names, so `recompute` falls back to its own tie-break and serves the shared source from one of them.
            val defaultWinner = session.parsers.pluginFor(SHARED_SOURCE)?.id
            val other = plugins.single { it.id != defaultWinner }
            check(controller.state.value.jarPriorityOrder.isEmpty()) { "a fresh data root stored an order" }
            println("DESKTOP_JAR_PRIORITY=default:$defaultWinner moved:${other.id}")
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                fun snapshot(label: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                // A Dialog is its own root, so onRoot() is ambiguous while one is open: capture the dialog instead.
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                // Android shows the stored list as the value and Android's empty text while nothing is stored.
                onNodeWithTag("source-setting-jar-priority").performScrollTo().assertExists()
                onNodeWithTag("source-setting-jar-priority").performClick()
                idle()
                onNodeWithTag("jar-priority-dialog").assertExists()
                onNodeWithTag("jar-priority-note").assertExists()
                onNodeWithTag("jar-priority:${plugins[0].id}").assertExists()
                onNodeWithTag("jar-priority:${plugins[1].id}").assertExists()
                // Each move writes Android's value, exactly like the category manager reorders without a confirm button.
                onNodeWithTag("jar-priority-up:${other.id}").performClick()
                idle()
                val stored = (session.storage.preferences.open("desktop_extensions").snapshot()
                    ["jar_priority_order"] as? SourcePreferenceValue.Text)?.value
                check(stored == "${other.id},$defaultWinner") { "stored order was $stored" }
                check(controller.state.value.jarPriorityOrder == stored) { "the page did not follow the write" }
                onNodeWithTag("jar-priority-up:${other.id}").assertExists()
                snapshotTag("jar-priority-dialog", "dialog")
                onNodeWithTag("jar-priority-close").performClick()
                idle()
                onNodeWithTag("jar-priority-dialog").assertDoesNotExist()
                onNodeWithText("${other.id}, $defaultWinner").assertExists()
                snapshot("row")
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            // The plugins the session restored; the extension page lists the same ids once it is opened.
            val plugins = session.parsers.installed().map { it.id }
            check(plugins.size == 2) { "the parser fixtures did not survive the restart: $plugins" }
            // The stored order leads, so the plugin it names first is the one the host ranks highest now.
            val order = session.jarPriorityNames()
            check(order.size == 2) { "the stored order resolved to $order" }
            val winner = session.parsers.pluginFor(SHARED_SOURCE)?.id
            check(winner == order.first()) { "$SHARED_SOURCE is served by $winner, the order starts with ${order.first()}" }
            // Mirror the registry's own ranking to see who would have served the source without the stored order.
            val byDefault = plugins.minWithOrNull(compareBy(
                { plugin -> ParserPluginRegistry.DEFAULT_PRIORITY.indexOfFirst { it.equals(plugin, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: Int.MAX_VALUE },
                { plugin -> plugin.lowercase() },
            ))
            check(winner != byDefault) { "the default ranking already served $SHARED_SOURCE from $winner" }
            println("DESKTOP_JAR_PRIORITY=order:$order winner:$winner default:$byDefault")
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                onNodeWithTag("source-setting-jar-priority").performScrollTo().assertExists()
                onNodeWithText(order.joinToString(", ")).assertExists()
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /** The source both parser fixtures declare, which is what the order decides. */
    private const val SHARED_SOURCE = "FIXTURE_SHARED"
}
