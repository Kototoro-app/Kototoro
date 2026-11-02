package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's `show_broken_sources` over a real plugin jar. The write phase installs the fixture whose parser carries
 * `@Broken`, checks the source is hidden by default, flips the switch on the source settings page and checks both the
 * publishing and Android's stored value; the read phase opens the same data root and checks the switch, the "（已损坏）"
 * title and the parser still being servable without a re-import.
 */
internal object DesktopBrokenSourcesProbe {
    fun run(args: Array<String>) {
        if (args[0] == "brokensource-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val fixtures = Path.of(args[9])
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(fixtures.resolve("fixture-broken.jar")).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            // The host read the annotation: the source exists and is flagged, and it is still callable (Android keeps
            // broken parsers usable, the switch only decides whether the lists offer them).
            val info = session.parsers.sources().single { it.source.name == BROKEN_SOURCE }
            check(info.broken) { "the host did not flag $BROKEN_SOURCE as broken" }
            check(session.sourceListings().none { it.source.name == BROKEN_SOURCE }) {
                "a broken source was offered while show_broken_sources is off"
            }
            check(session.sourceListings(includeBroken = true).any { it.source.name == BROKEN_SOURCE })
            println("DESKTOP_BROKEN_SOURCES=flagged:${info.broken} hidden:true")
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
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
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                onNodeWithTag("source-setting-show-broken").performScrollTo().assertExists()
                snapshot("off")
                onNodeWithTag("source-setting-show-broken").performClick()
                idle()
                check(controller.state.value.showBrokenSources) { "the switch did not turn on" }
                val stored = (session.storage.preferences.open("desktop_extensions").snapshot()
                    [SHOW_BROKEN_SOURCES_KEY] as? SourcePreferenceValue.Toggle)?.value
                check(stored == true) { "stored switch was $stored" }
                val listed = controller.state.value.sources.single { it.source.name == BROKEN_SOURCE }
                check(listed.displayName == "Fixture Broken (已损坏)") { "offered as ${listed.displayName}" }
                snapshot("on")
                // Back off: the source leaves every list again, and the switch keeps Android's "off" state.
                onNodeWithTag("source-setting-show-broken").performClick()
                idle()
                check(!controller.state.value.showBrokenSources) { "the switch did not turn off" }
                check(controller.state.value.sources.none { it.source.name == BROKEN_SOURCE })
                onNodeWithTag("source-setting-show-broken").performClick()
                idle()
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            check(session.showBrokenSources()) { "show_broken_sources did not survive the restart" }
            val listed = session.sourceListings().single { it.source.name == BROKEN_SOURCE }
            check(listed.displayName == "Fixture Broken (已损坏)") { "offered as ${listed.displayName}" }
            check(session.parsers.pluginFor(BROKEN_SOURCE) != null) { "the broken parser was not restored" }
            println("DESKTOP_BROKEN_SOURCES_PERSISTED=${listed.displayName}")
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /** The single source the broken fixture declares. */
    private const val BROKEN_SOURCE = "FIXTURE_BROKEN"
}
