package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopNavItem
import org.skepsun.kototoro.desktop.runtime.DesktopNavigationSettings
import org.skepsun.kototoro.desktop.runtime.defaultDesktopNavItems
import java.nio.file.Path

/**
 * Android's nav configuration page on Windows: 更多 → "main screen sections" edits the rail.
 *
 * The probe walks the same edits Android allows — remove a destination, add one back (it lands last), move it up, move
 * another down — and checks four things after each one: the row list, the stored `nav_main` document, the rail that
 * sits next to the page, and finally what the store holds once the page is left. 更多 must stay on the rail
 * throughout: it is the only exit, so removing every configured destination may not strand the user.
 */
internal object DesktopNavigationConfigProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val directory = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                fun stored(): List<DesktopNavItem> = runBlocking { session.navigationPreferences.current().items }
                fun awaitItems(expected: List<DesktopNavItem>) {
                    waitUntil(timeoutMillis = 15_000) { stored() == expected }
                    idle()
                }

                idle()
                check(stored() == defaultDesktopNavItems) {
                    "a fresh data root should hold the default order: ${stored()}"
                }
                check(stored() == DesktopNavigationSettings().items) { "the default order should be the rail's own" }
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("nav-config-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.NAV_CONFIG }
                idle()
                defaultDesktopNavItems.forEach { item -> onNodeWithTag("nav-config-item:${item.name}").assertExists() }
                // Five destinations already fill the cap, so there is nothing to add and no add row to offer.
                onNodeWithTag("nav-config-add").assertDoesNotExist()
                onNodeWithTag("nav-config-pinned").performScrollTo().assertExists()
                onNodeWithTag("nav-config-cap").performScrollTo().assertExists()
                println("DESKTOP_NAV_CONFIG=defaults:${stored()}")

                // Remove the first destination: the row goes, the store follows, and so does the rail beside the page.
                onNodeWithTag("nav-config-remove:HOME").performClick()
                awaitItems(listOf(
                    DesktopNavItem.FAVOURITES,
                    DesktopNavItem.BROWSE,
                    DesktopNavItem.HISTORY,
                    DesktopNavItem.SUBSCRIPTIONS,
                ))
                onNodeWithTag("nav-config-item:HOME").assertDoesNotExist()
                onNodeWithTag("nav:${desktopNavItemLabel(DesktopNavItem.HOME)}").assertDoesNotExist()
                onNodeWithTag("nav:更多").assertExists()

                // Adding it back appends it, like Android's add; the rail gains the tab again.
                onNodeWithTag("nav-config-add").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("nav-config-add-option:HOME").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("nav-config-add-option:HOME").performClick()
                awaitItems(listOf(
                    DesktopNavItem.FAVOURITES,
                    DesktopNavItem.BROWSE,
                    DesktopNavItem.HISTORY,
                    DesktopNavItem.SUBSCRIPTIONS,
                    DesktopNavItem.HOME,
                ))
                onNodeWithTag("nav:${desktopNavItemLabel(DesktopNavItem.HOME)}").assertExists()

                // Up and down swap with the neighbour, and the first/last row cannot move further.
                onNodeWithTag("nav-config-up:HOME").performScrollTo().performClick()
                awaitItems(listOf(
                    DesktopNavItem.FAVOURITES,
                    DesktopNavItem.BROWSE,
                    DesktopNavItem.HISTORY,
                    DesktopNavItem.HOME,
                    DesktopNavItem.SUBSCRIPTIONS,
                ))
                onNodeWithTag("nav-config-down:FAVOURITES").performClick()
                val configured = listOf(
                    DesktopNavItem.BROWSE,
                    DesktopNavItem.FAVOURITES,
                    DesktopNavItem.HISTORY,
                    DesktopNavItem.HOME,
                    DesktopNavItem.SUBSCRIPTIONS,
                )
                awaitItems(configured)
                println("DESKTOP_NAV_CONFIG=edited:${stored()}")

                // Android's document: one comma-joined string under nav_main, in the configured order.
                val raw = runBlocking { session.storage.preferences.open("desktop_nav").snapshot()["nav_main"] }
                check(raw?.toString()?.contains(DesktopNavigationSettings.format(configured)) == true) {
                    "nav_main should hold the configured order, found $raw"
                }

                onNodeWithTag("nav-config-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                check(stored() == configured) { "leaving the page must not change the list: ${stored()}" }
                // The rail the user came back to is the one the page edited, with 更多 still last.
                configured.forEach { item ->
                    onNodeWithTag("nav:${desktopNavItemLabel(item)}").assertExists()
                }
                onNodeWithTag("nav:更多").assertExists()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
