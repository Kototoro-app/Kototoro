package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's setup wizard on Windows (§183). The phone opens it by itself on first start and from 来源设置; Windows keeps
 * it a manual entry, so the probe drives it the only way in:
 *
 * - 来源设置 closes its overview group with Android's 设置向导 row, and the row opens the wizard screen;
 * - page one repeats Android's intro (app title, the repository/extension/source concepts, the disclaimer) with the
 *   wizard's own progress label and a disabled back button;
 * - page two edits the two settings Windows has in place of Android's transition/panorama switches, and picking a theme
 *   there stores it in the same `desktop_appearance` key 更多 writes;
 * - page three is Android's done page: the extension-management and restore-backup entries land on the two Windows
 *   screens that carry them, and its last button (完成) returns to 来源设置.
 *
 * Android's permissions page (runtime grants) and spaces page (Windows has one library) have no Windows counterpart, so
 * the wizard is three pages long instead of four.
 */
internal object DesktopWizardProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
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
                fun expectScreen(screen: DesktopScreen, what: String) {
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == screen }
                    idle()
                    check(controller.state.value.screen == screen) { "$what landed on ${controller.state.value.screen}" }
                }
                fun openWizard() {
                    onNodeWithTag("nav:更多").performClick()
                    expectScreen(DesktopScreen.MORE, "更多")
                    onNodeWithTag("source-settings-open").performScrollTo().performClick()
                    expectScreen(DesktopScreen.SOURCE_SETTINGS, "来源设置")
                    onNodeWithTag("source-setting-wizard").performScrollTo().performClick()
                    expectScreen(DesktopScreen.SETUP_WIZARD, "设置向导")
                }
                fun expectStep(index: Int) {
                    onNodeWithTag("wizard-progress")
                        .assertTextEquals(desktopText("sources.wizardStep", index, 3))
                }
                fun next() {
                    onNodeWithTag("wizard-next").performScrollTo().performClick()
                    idle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                expectScreen(DesktopScreen.MORE, "更多")
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                expectScreen(DesktopScreen.SOURCE_SETTINGS, "来源设置")
                // Android's overview group ends with the wizard entry; it has to be reachable without leaving the page.
                onNodeWithTag("source-setting-wizard").performScrollTo().assertExists()
                snapshot("settings")
                onNodeWithTag("source-setting-wizard").performScrollTo().performClick()
                expectScreen(DesktopScreen.SETUP_WIZARD, "设置向导")
                expectStep(1)
                onNodeWithTag("wizard-back").assertIsNotEnabled()
                onNodeWithText(AndroidStrings["welcome_intro_app_title"]).assertExists()
                onNodeWithText(AndroidStrings["welcome_intro_disclaimer"]).assertExists()
                snapshot("intro")
                next()
                expectStep(2)
                onNodeWithTag("wizard-back").performScrollTo().assertExists()
                onNodeWithTag("wizard-theme").performScrollTo().assertExists()
                onNodeWithTag("wizard-language").performScrollTo().assertExists()
                snapshot("appearance")
                // The wizard's theme row writes the same preference 更多 does, so a pick here has to stick.
                onNodeWithTag("wizard-theme").performScrollTo().performClick()
                idle()
                onNodeWithTag("wizard-theme-dialog").assertExists()
                onNodeWithTag("wizard-theme:${DesktopAppearance.LIGHT.name}").performClick()
                idle()
                check(controller.state.value.appearance == DesktopAppearance.LIGHT) {
                    "the wizard kept ${controller.state.value.appearance}"
                }
                check((session.storage.preferences.open("desktop_appearance").snapshot()["theme"]
                    as? SourcePreferenceValue.Text)?.value == DesktopAppearance.LIGHT.name) {
                    "the wizard's theme was not stored"
                }
                next()
                expectStep(3)
                onNodeWithText(AndroidStrings["welcome_done_title_finished"]).assertExists()
                onNodeWithTag("wizard-open-extensions").performScrollTo().assertExists()
                onNodeWithTag("wizard-restore-backup").performScrollTo().assertExists()
                onNodeWithTag("wizard-next").assertTextEquals(AndroidStrings["done"])
                snapshot("done")
                // Android's done page is a pair of jumps; both have a Windows screen to land on.
                onNodeWithTag("wizard-open-extensions").performScrollTo().performClick()
                expectScreen(DesktopScreen.EXTENSIONS, "去拓展管理")
                openWizard()
                expectStep(1)
                next()
                next()
                expectStep(3)
                onNodeWithTag("wizard-restore-backup").performScrollTo().performClick()
                expectScreen(DesktopScreen.BACKUPS, "恢复备份")
                // The wizard's own back button steps backwards instead of leaving the screen, and 完成 closes it.
                openWizard()
                next()
                next()
                expectStep(3)
                onNodeWithTag("wizard-back").performScrollTo().performClick()
                idle()
                expectStep(2)
                next()
                expectStep(3)
                next()
                expectScreen(DesktopScreen.SOURCE_SETTINGS, "完成")
                snapshot("returned")
            }
            println("DESKTOP_WIZARD=appearance:${controller.state.value.appearance}")
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
