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
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's explicit-enable rule for parsers marked `@Broken`, over a real plugin jar. The write phase installs the
 * fixture whose parser carries `@Broken`, checks it is listed ("（已损坏）") but switched off, checks the extension
 * settings page (reached from 扩展与仓库) holds the plugin priority and install behavior rows and no broken-sources
 * switch, switches the source on, and replays the one-time migration for a user who had the retired switch on and one
 * who had it off. The read phase opens the same data root and checks the switch-on survived without a re-import.
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
            // The host read the annotation: the source exists, is flagged and listed, and it is still callable.
            val info = session.parsers.sources().single { it.source.name == BROKEN_SOURCE }
            check(info.broken) { "the host did not flag $BROKEN_SOURCE as broken" }
            val listed = session.sourceListings().single { it.source.name == BROKEN_SOURCE }
            check(listed.displayName == "Fixture Broken (已损坏)") { "listed as ${listed.displayName}" }
            // First seen, so switched off: listed in extension management, not offered for browsing.
            check(BROKEN_SOURCE in session.disabledSources()) { "a broken parser was enabled on install" }
            check(controller.state.value.enabledSources.none { it.source.name == BROKEN_SOURCE }) {
                "a broken parser was offered before the user switched it on"
            }
            println("DESKTOP_BROKEN_SOURCES=flagged:${info.broken} disabled:true")
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
                // The source settings no longer carry the extension rows.
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                onNodeWithTag("source-setting-show-broken").assertDoesNotExist()
                onNodeWithTag("source-setting-jar-priority").assertDoesNotExist()
                onNodeWithTag("source-setting-install-behavior").assertDoesNotExist()
                onNodeWithTag("source-settings-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                // They moved to 扩展与仓库 → 拓展设置, next to 添加 on every tab.
                onNodeWithTag("nav:扩展与仓库").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.EXTENSIONS }
                idle()
                onNodeWithTag("extension-settings-open").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.EXTENSION_SETTINGS }
                idle()
                onNodeWithTag("source-setting-jar-priority").performScrollTo().assertExists()
                onNodeWithTag("source-setting-install-behavior").performScrollTo().assertExists()
                onNodeWithTag("source-setting-show-broken").assertDoesNotExist()
                snapshot("extension-settings")
                onNodeWithTag("extension-settings-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.EXTENSIONS }
                idle()
            }
            // The user switches the broken parser on; it is offered from then on.
            runBlocking { controller.setSourceEnabled(BROKEN_SOURCE, true).join() }
            check(BROKEN_SOURCE !in session.disabledSources()) { "the switch-on was not stored" }
            check(controller.state.value.enabledSources.any { it.source.name == BROKEN_SOURCE }) {
                "a broken parser the user switched on was not offered"
            }
            // Already seen, so a later reconcile leaves the user's choice alone.
            session.reconcileBrokenSources()
            check(BROKEN_SOURCE !in session.disabledSources()) { "reconcile undid the user's switch-on" }
            replayMigration(session)
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /**
     * The first run after the switch was retired: no `broken_sources_seen` yet, the broken parser enabled by default.
     * Whoever had `show_broken_sources` on keeps seeing it; whoever had it off finds it switched off. The probe ends
     * with the source switched on again for the read phase.
     */
    private fun replayMigration(session: DesktopSession) {
        fun firstRun(showBroken: Boolean) {
            val disabled = session.disabledSources() - BROKEN_SOURCE
            session.storage.preferences.open("desktop_extensions").edit(SourcePreferenceEdit(changes = mapOf(
                SEEN_BROKEN_SOURCES_KEY to null,
                SHOW_BROKEN_SOURCES_KEY to SourcePreferenceValue.Toggle(showBroken),
                "disabled_sources" to SourcePreferenceValue.TextSet(disabled),
            )))
            session.reconcileBrokenSources()
        }
        firstRun(showBroken = true)
        check(BROKEN_SOURCE !in session.disabledSources()) { "the migration hid a parser the switch showed" }
        firstRun(showBroken = false)
        check(BROKEN_SOURCE in session.disabledSources()) { "the migration offered a parser the switch hid" }
        val seen = (session.storage.preferences.open("desktop_extensions").snapshot()[SEEN_BROKEN_SOURCES_KEY]
            as? SourcePreferenceValue.TextSet)?.values.orEmpty()
        check(BROKEN_SOURCE in seen) { "the migration did not record the parser as seen" }
        session.setSourceEnabled(BROKEN_SOURCE, true)
        println("DESKTOP_BROKEN_SOURCES_MIGRATION=kept-on:true hidden-off:true")
    }

    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            check(BROKEN_SOURCE !in session.disabledSources()) { "the switch-on did not survive the restart" }
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
