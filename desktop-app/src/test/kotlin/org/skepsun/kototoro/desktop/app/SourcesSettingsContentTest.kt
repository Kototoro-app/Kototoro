package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class SourcesSettingsContentTest {
    private val initial = SourcesSettingsContentState("DEFAULT", false, true, false, false,
        "Ask", "ASK", 0, true)
    private val titles = SourcesSettingsTitles("Overview", "Remote", "Adult", "More")
    private val sorts = listOf(PreferenceChoice("DEFAULT", "Default"), PreferenceChoice("CUSTOM", "Custom"))
    private val incognito = listOf(PreferenceChoice("ASK", "Ask"), PreferenceChoice("ENABLED", "Enabled"))

    @Test
    fun sharedPageEditsCommonSettingsAndRoutesActionsInNarrowAndWideWindows() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var state by mutableStateOf(initial)
            var reorders = 0
            var wizards = 0
            var installs = 0
            var blacklists = 0
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        SourcesSettingsContent(state, titles, sorts, incognito,
                            { state = state.copy(sortOrder = it, customSort = it == "CUSTOM") },
                            { state = state.copy(gridMode = it) }, { state = state.copy(groupByLanguage = it) },
                            { reorders++ }, { wizards++ }, { state = state.copy(showBrokenSources = it) },
                            { installs++ }, { state = state.copy(incognitoMode = it) }, { blacklists++ },
                            { state = state.copy(tagsWarnings = it) },
                            jarPriorityEditor = { Text("Priority editor") },
                            adultFilterEditor = { Text("Filter editor") },
                            reorderSummary = "Move buttons", wizardSummary = "Desktop wizard details")
                    }
                }
            }
            onNodeWithTag("source-setting-reorder").assertDoesNotExist()
            onNodeWithTag("source-setting-sort").performScrollTo().performClick()
            onNodeWithTag("source-sort-close").performClick()
            runOnIdle { assertEquals("DEFAULT", state.sortOrder) }
            onNodeWithTag("source-setting-sort").performClick()
            onNodeWithTag("source-sort-option:CUSTOM").performClick()
            onNodeWithTag("source-setting-reorder").performScrollTo().performClick()
            onNodeWithTag("source-setting-reorder").assertTextContains("Move buttons")
            onNodeWithTag("source-setting-grid").performScrollTo().performClick()
            onNodeWithTag("source-setting-group-language").performScrollTo().performClick()
            onNodeWithTag("source-setting-wizard").performScrollTo().performClick()
            onNodeWithTag("source-setting-wizard").assertTextContains("Desktop wizard details")
            onNodeWithTag("source-setting-show-broken").performScrollTo().performClick()
            onNodeWithTag("source-setting-install-behavior").performScrollTo().performClick()
            onNodeWithTag("source-setting-incognito-nsfw").performScrollTo().performClick()
            onNodeWithTag("source-incognito-option:ENABLED").performClick()
            onNodeWithTag("source-setting-tag-blacklist").performScrollTo().performClick()
            onNodeWithTag("source-setting-tags-warnings").performScrollTo().performClick()
            onNodeWithTag("source-setting-mirror").assertDoesNotExist()
            onNodeWithTag("source-setting-links").assertDoesNotExist()
            runOnIdle {
                assertEquals(initial.copy(sortOrder = "CUSTOM", customSort = true, gridMode = false,
                    groupByLanguage = true, showBrokenSources = true, incognitoMode = "ENABLED", tagsWarnings = false), state)
                assertEquals(listOf(1, 1, 1, 1), listOf(reorders, wizards, installs, blacklists))
            }
            onNodeWithTag("source-setting-sort").performScrollTo()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-sources-settings-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun AndroidCapabilitiesKeepGridInformationalAndExposeMirrorAndLinkSwitches() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var state by mutableStateOf(initial.copy(gridMode = null, mirrorSwitching = true, handleLinks = false))
            var gridWrites = 0
            setContent {
                DesktopTheme {
                    SourcesSettingsContent(state, titles, sorts, incognito, {}, { gridWrites++ }, {}, {}, {}, {}, {},
                        {}, {}, {}, jarPriorityEditor = {}, adultFilterEditor = {},
                        onMirrorChange = { state = state.copy(mirrorSwitching = it) },
                        onHandleLinksChange = { state = state.copy(handleLinks = it) })
                }
            }
            onNodeWithTag("source-setting-grid").performScrollTo().assertHasNoClickAction()
            onNodeWithTag("source-setting-mirror").performScrollTo().assertIsOn().performClick()
            onNodeWithTag("source-setting-links").performScrollTo().assertIsOff().performClick()
            runOnIdle {
                assertEquals(false, state.mirrorSwitching)
                assertEquals(true, state.handleLinks)
                assertEquals(0, gridWrites)
                state = state.copy(mirrorSwitching = null, handleLinks = null)
            }
            onNodeWithTag("source-setting-mirror").assertDoesNotExist()
            onNodeWithTag("source-setting-links").assertDoesNotExist()
        }

    @Test
    fun busyStateDisablesOpenChoicesAndCommonRows() = runDesktopComposeUiTest(width = 380, height = 800) {
        var enabled by mutableStateOf(true)
        var writes = 0
        setContent {
            DesktopTheme {
                SourcesSettingsContent(initial, titles, sorts, incognito, { writes++ }, { writes++ }, { writes++ },
                    { writes++ }, { writes++ }, { writes++ }, { writes++ }, { writes++ }, { writes++ }, { writes++ },
                    jarPriorityEditor = {}, adultFilterEditor = {}, enabled = enabled)
            }
        }
        onNodeWithTag("source-setting-sort").performScrollTo().performClick()
        runOnIdle { enabled = false }
        onNodeWithTag("source-sort-option:CUSTOM").assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag("source-sort-close").performClick()
        for (row in listOf("sort", "grid", "group-language", "wizard", "show-broken", "install-behavior",
            "incognito-nsfw", "tag-blacklist", "tags-warnings")) {
            onNodeWithTag("source-setting-$row").performScrollTo().assertIsNotEnabled()
        }
        runOnIdle { assertEquals(0, writes) }
    }
}
