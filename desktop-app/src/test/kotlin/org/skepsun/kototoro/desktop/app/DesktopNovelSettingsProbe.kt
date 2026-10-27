package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Path

/** The real options panel with its controller and persistent store, without unrelated source/parser setup. */
internal object DesktopNovelSettingsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (args[0] == "novel-settings-write") {
                runDesktopComposeUiTest(width = 1260, height = 850) {
                    setContent {
                        DesktopTheme {
                            val state by controller.state.collectAsState()
                            DesktopNovelOptionsPanel(controller, state.novelSettings, false, null, {})
                        }
                    }
                    waitForIdle()
                    onNode(hasText("+") and hasAnyAncestor(hasTestTag("novel-font-size"))).performClick()
                    onNode(hasText("+") and hasAnyAncestor(hasTestTag("novel-line-spacing"))).performClick()
                    onNodeWithText(AndroidStrings["novel_reader_tab_reading"]).performClick()
                    onNodeWithText(AndroidStrings["novel_mode_paged"]).performClick(); waitForIdle()
                    check(controller.state.value.novelSettings.paged)
                    onNodeWithText(AndroidStrings["novel_mode_scroll"]).performClick(); waitForIdle()
                    check(!controller.state.value.novelSettings.paged)
                    onNodeWithText(AndroidStrings["novel_mode_paged"]).performClick(); waitForIdle()
                    checkSettings(controller)
                }
            } else {
                checkSettings(controller)
                val stored = session.storage.preferences.open("desktop_novel").snapshot()
                check((stored["font_size"] as? SourcePreferenceValue.Integer)?.value == 20)
                check((stored["paged"] as? SourcePreferenceValue.Toggle)?.value == true)
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=" + args[0])
    }

    private fun checkSettings(controller: DesktopController) {
        val settings = controller.state.value.novelSettings
        check(settings.fontSize == 20)
        check(kotlin.math.abs(settings.lineSpacing - 1.8f) < .001f)
        check(settings.paged)
        check(settings.width == DesktopNovelSettings().width)
        check(settings.theme == DesktopNovelSettings().theme)
    }
}
