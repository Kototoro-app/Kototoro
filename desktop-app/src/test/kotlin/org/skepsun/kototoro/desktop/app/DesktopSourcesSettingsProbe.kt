package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSort
import org.skepsun.kototoro.desktop.runtime.DesktopTriState
import java.nio.file.Path

internal object DesktopSourcesSettingsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (args[0] == "sources-settings-write") runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick(); idle()
                onNodeWithTag("source-setting-sort").performScrollTo().performClick()
                onNodeWithTag("source-sort-option:TITLE").performClick(); idle()
                onNodeWithTag("source-setting-grid").performScrollTo().assertIsOn().performClick(); idle()
                onNodeWithTag("source-setting-group-language").performScrollTo().assertIsOff().performClick(); idle()
                onNodeWithTag("source-setting-tags-warnings").performScrollTo().assertIsOn().performClick(); idle()
                onNodeWithTag("source-setting-incognito-nsfw").performScrollTo().performClick()
                onNodeWithTag("source-incognito-option:DISABLED").performClick(); idle()
                checkSettings(controller)
                onNodeWithTag("source-settings-back").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.MORE)
            } else checkSettings(controller)
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=" + args[0])
    }

    private fun checkSettings(controller: DesktopController) {
        val settings = controller.state.value.sourceSettings
        check(settings.sort == DesktopSourceSort.TITLE)
        check(!settings.grid && settings.groupByLanguage && !settings.tagsWarnings)
        check(settings.incognitoForNsfw == DesktopTriState.DISABLED)
    }
}
