package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopNavItem
import org.skepsun.kototoro.desktop.runtime.DesktopNavigationSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionFilter
import org.skepsun.kototoro.desktop.runtime.acceptsSuggestionFilters
import org.skepsun.kototoro.desktop.runtime.desktopSuggestionFilters
import java.nio.file.Path

/**
 * Android's suggestions destination against a real library (§198): one favourite seeds the worker, the home rail's "all"
 * opens the page it filled, and the page behaves like Android's screen:
 *
 * - the header is Android's card (its subtitle, 换一批, 推荐设置), and the grid is the stored list;
 * - the quick-filter chips are Android's five top tags, its two adult macros and its three top sources, and a chip that
 *   hides works really empties the grid — the adult macros, because the fixture's works are adult;
 * - tapping the same chip again restores the list, and 换一批 leaves the page with a usable list;
 * - 推荐设置 reaches the settings page, a card opens the work and returns to the same list, and the destination is
 *   reachable from the rail once the nav configuration adds it (Android's `NavItem.SUGGESTIONS`).
 */
internal object DesktopSuggestionsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            val sourceName = session.sourceListings().single().source.name
            // The worker scores candidates against the tags of recent history and favourites, so one favourite seeds it.
            val seed = runBlocking { session.sources.getList(sourceName, 0, null, null) }.firstOrNull()
            checkNotNull(seed) { "the fixture source returns a list" }
            runBlocking { session.library.addFavourite(seed) }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun awaitRun() {
                    // `refreshSuggestions` reports through `suggestionsGenerating` rather than `busy`.
                    waitUntil(timeoutMillis = 60_000) { !controller.state.value.suggestionsGenerating }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                runBlocking { controller.home().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.HOME }
                idle()
                // Recommendations start switched off, so the rail asks for them exactly like Android's empty card.
                check(controller.state.value.suggestions.isEmpty()) { "the root starts without suggestions" }
                onNodeWithTag("home-recommendations-empty").performScrollTo().assertExists()
                onNodeWithTag("home-recommendations-generate").performScrollTo().performClick(); awaitRun()
                waitUntil(timeoutMillis = 30_000) { controller.state.value.suggestions.isNotEmpty() }
                idle()
                onNodeWithTag("home-recommendations").performScrollTo().assertExists()
                val stored = controller.state.value.suggestions
                // Android's home rail opens the suggestions destination from its "all".
                onNodeWithTag("home-recommendations-more").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SUGGESTIONS }
                idle()
                onNodeWithTag("suggestions-panel").assertExists()
                onNodeWithTag("suggestions-summary").assertExists()
                check(controller.state.value.suggestions.size == stored.size) { "the page re-read the same rows" }
                val first = stored.first()
                onNodeWithTag("content:${first.id}").assertExists()
                // The chips are Android's own composition for this list: top tags, both adult macros, top sources.
                val chips = desktopSuggestionFilters(stored,
                    includeAdultChips = !controller.state.value.suggestionSettings.excludeNsfw)
                check(chips.contains(DesktopSuggestionFilter.Adult)) { "adult chips follow the nsfw setting" }
                check(chips.contains(DesktopSuggestionFilter.Safe))
                chips.filterIsInstance<DesktopSuggestionFilter.Source>().forEach {
                    onNodeWithTag("suggestion-chip:source:${it.name}").assertExists()
                }
                // A chip that really splits this list — a tag on some works but not all, else the adult macro that does
                // not match every work here. Both are Android's conditions, so whichever the fixture yields exercises
                // the same rule; a list that one of the macros empties is split too, by definition.
                fun kept(chip: DesktopSuggestionFilter) = stored.filter { it.acceptsSuggestionFilters(setOf(chip)) }
                val candidates = chips.filterIsInstance<DesktopSuggestionFilter.Tag>() +
                    listOf(DesktopSuggestionFilter.Adult, DesktopSuggestionFilter.Safe)
                val split = candidates.firstOrNull { kept(it).size in 1 until stored.size }
                    ?: candidates.firstOrNull { kept(it).isEmpty() }
                requireNotNull(split) { "one of the chips must split a non-empty list" }
                val survivors = kept(split).mapTo(HashSet()) { it.id }
                onNodeWithTag("suggestion-chip:${split.testKey()}").performClick(); idle()
                check(controller.state.value.suggestionFilters == setOf(split))
                // The first row is always on screen, so it is where the narrowing has to show up.
                stored.take(6).forEach { work ->
                    if (work.id in survivors) onNodeWithTag("content:${work.id}").assertExists()
                    else onNodeWithTag("content:${work.id}").assertDoesNotExist()
                }
                // Tapping the same chip again is Android's toggle-off, which brings the whole list back.
                onNodeWithTag("suggestion-chip:${split.testKey()}").performClick(); idle()
                check(controller.state.value.suggestionFilters.isEmpty())
                stored.take(6).forEach { onNodeWithTag("content:${it.id}").assertExists() }
                // Android's 换一批 rebuilds in place, and the page stays usable afterwards.
                onNodeWithTag("suggestions-refresh").performClick(); awaitRun()
                waitUntil(timeoutMillis = 30_000) { controller.state.value.suggestions.isNotEmpty() }
                idle()
                onNodeWithTag("suggestions-panel").assertExists()
                check(controller.state.value.screen == DesktopScreen.SUGGESTIONS)
                // The header's settings link is Android's 推荐设置 destination.
                onNodeWithTag("suggestions-settings").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.RECOMMENDATIONS)
                runBlocking { controller.showSuggestions().join() }; idle()
                check(controller.state.value.screen == DesktopScreen.SUGGESTIONS)
                // A card opens the work, and dismissing it comes back to this list (Android's list state survives).
                val open = controller.state.value.suggestions.first()
                onNodeWithTag("content:${open.id}").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                check(controller.state.value.detailsOrigin == DesktopScreen.SUGGESTIONS)
                runBlocking { controller.dismissDetails().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SUGGESTIONS }
                idle()
                onNodeWithTag("content:${open.id}").assertExists()
                // Android's nav configuration offers the destination, and the rail opens it once it is added.
                runBlocking {
                    controller.saveNavigationSettings(
                        DesktopNavigationSettings(
                            items = listOf(DesktopNavItem.HOME, DesktopNavItem.FAVOURITES, DesktopNavItem.BROWSE,
                                DesktopNavItem.HISTORY, DesktopNavItem.SUGGESTIONS),
                        ),
                    ).join()
                }
                idle()
                onNodeWithTag("nav:${AndroidStrings["suggestions"]}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SUGGESTIONS }
                idle()
                onNodeWithTag("suggestions-panel").assertExists()
                // With the feature off the page asks for it instead of hiding the destination (Android's tab does the
                // same), and the nav configuration marks the row unavailable.
                controller.suggestionSettings(controller.state.value.suggestionSettings.copy(enabled = false))
                runBlocking { controller.showSuggestions().join() }; idle()
                onNodeWithTag("suggestions-disabled").assertExists()
                // The configuration page keeps the configured destination and marks it with Android's hint.
                runBlocking { controller.showNavigationConfig().join() }; idle()
                onNodeWithTag("nav-config-item:SUGGESTIONS").performScrollTo().assertExists()
                onNodeWithText(AndroidStrings["suggestions_unavailable_text"]).assertExists()
                runBlocking { controller.showSuggestions().join() }; idle()
                onNodeWithTag("suggestions-enable").performClick(); awaitRun()
                waitUntil(timeoutMillis = 30_000) { controller.state.value.suggestions.isNotEmpty() }
                idle()
                onNodeWithTag("suggestions-panel").assertExists()
                check(controller.state.value.suggestionSettings.enabled) { "the page's own button switches it on" }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=$mode")
    }

    /** The page's own chip tag suffix (`DesktopSuggestionFilter.key()` is private to the page). */
    private fun DesktopSuggestionFilter.testKey(): String = when (this) {
        is DesktopSuggestionFilter.Tag -> "tag:$title"
        is DesktopSuggestionFilter.Source -> "source:$name"
        DesktopSuggestionFilter.Adult -> "nsfw"
        DesktopSuggestionFilter.Safe -> "sfw"
    }
}
