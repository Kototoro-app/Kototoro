package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourceTag as ContentTag
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import java.nio.file.Files
import java.nio.file.Path

/** Exercises authored reader pages through the real SDK, UI and database; never opens user data. */
internal object DesktopTabletProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val narrow = args[0] == "tablet-narrow" || args[0] == "tablet-boundary-compact"
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runBlocking { controller.appearance(DesktopAppearance.LIGHT).join() }
            runDesktopComposeUiTest(width = when {
                args[0] == "tablet-boundary-compact" -> 999
                narrow -> 920
                args[0] == "tablet-boundary" -> 1000
                else -> 1260
            }, height = if (narrow) 620 else 850, testTimeout = 5.minutes) {
                var fullscreen by mutableStateOf(false)
                setContent {
                    DesktopApp(controller, fullscreen = fullscreen, onToggleFullscreen = { fullscreen = !fullscreen })
                }
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
                // A Dialog is its own root, so onRoot() is ambiguous while one is open: capture the dialog.
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                idle()
                val rail = onNodeWithTag("tablet-navigation").fetchSemanticsNode().boundsInRoot
                check(rail.width == 80f)
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }; idle()
                snapshot("browse")
                val sourceTag = "source:${controller.state.value.sources.single().source.name}"
                // The rail carries Android's two top-bar filters and honours them: a filter that excludes every
                // installed source empties the rail instead of leaving the list untouched.
                onNodeWithTag("rail-content-type").assertExists()
                onNodeWithTag("rail-source-tags").assertExists()
                controller.exploreFilter(DesktopSourceFilter(tags = setOf(SourceTag.CLOUDSTREAM),
                    tab = BrowseGroupTab.Video)); idle()
                onNodeWithTag(sourceTag).assertDoesNotExist()
                onNodeWithText(desktopText("browse.sourcesEmpty")).assertExists()
                snapshot("browse-rail-filtered")
                controller.exploreFilter(DesktopSourceFilter()); idle()
                onNodeWithTag(sourceTag).assertExists()
                // Leaving a source returns to the browse page's source grid: the back button, or re-selecting "浏览".
                onNodeWithTag("source-back").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.EXPLORE && controller.state.value.selectedSource == null)
                onNodeWithTag("source-back").assertDoesNotExist()
                onNodeWithTag(sourceTag).performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }; idle()
                onNodeWithText("浏览").performClick(); idle()
                check(controller.state.value.selectedSource == null)
                onNodeWithTag(sourceTag).performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }; idle()
                val content = controller.state.value.items.single()
                onNodeWithTag("source-query").performTextInput("unsent draft")
                val requestsBeforePreview = System.getProperty("fixture.reader.requests")
                val progressBeforePreview = runBlocking { session.library.progress(content.id) }
                val cardBeforePreview = onNodeWithTag("content:${content.id}").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("content:${content.id}").performClick(); idle()
                onNodeWithTag(if (narrow) "details-full" else "details-preview").assertExists()
                onNodeWithTag("source-pane").assertExists()
                if (!narrow) {
                    onNodeWithTag("tablet-preview-content").assertExists()
                    check(onNodeWithTag("details-preview").fetchSemanticsNode().boundsInRoot.width == 380f)
                    snapshot("preview")
                }
                check(controller.state.value.detailsOrigin == DesktopScreen.EXPLORE)
                check(System.getProperty("fixture.reader.requests") == requestsBeforePreview)
                check(runBlocking { session.library.progress(content.id) } == progressBeforePreview)
                snapshot("preview")
                onNodeWithTag("details-overlay").performKeyInput { pressKey(Key.Escape) }; idle()
                check(controller.state.value.screen == DesktopScreen.EXPLORE)
                check(onNodeWithTag("source-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text ==
                    "unsent draft")
                check(onNodeWithTag("content:${content.id}").fetchSemanticsNode().boundsInRoot == cardBeforePreview)
                onNodeWithTag("content:${content.id}").performClick(); idle()
                if (!narrow) {
                    onNodeWithTag("details-dismiss").performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 10f)) }
                    idle()
                    check(controller.state.value.screen == DesktopScreen.EXPLORE)
                    onNodeWithTag("content:${content.id}").performClick(); idle()
                    onNodeWithTag("details-expand").performClick(); idle()
                }
                onNodeWithTag("details-full").assertExists()
                onNodeWithTag(if (narrow) "details-stacked" else "details-two-pane").assertExists()
                snapshot("details")
                onNodeWithTag("preview-read").performClick(); idle()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("tablet-navigation").assertDoesNotExist()
                onNodeWithTag("source-back").assertDoesNotExist()
                val progress = runBlocking { session.library.progress(content.id) }
                val requests = System.getProperty("fixture.reader.requests")
                val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                check(viewport.left == 0f)
                snapshot("reader")
                // Android's chrome floats over the page: hiding it leaves the viewport as it was and shows the info bar.
                readerKey(Key.H)
                onNodeWithTag("reader-back").assertDoesNotExist()
                onNodeWithTag("reader-info-bar").assertExists()
                check(onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot == viewport)
                check(System.getProperty("fixture.reader.requests") == requests)
                check(runBlocking { session.library.progress(content.id) } == progress)
                snapshot("immersive")
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.H) }; idle()
                onNodeWithTag("reader-page-slider").assertExists()
                // Full screen is Android's "fullscreen mode" switch in the options panel (F11 too).
                readerOptions {
                    // Fullscreen is below the initially composed layout items in the lazy options list.
                    val optionsList = onNode(hasScrollToIndexAction() and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                        hasAnyAncestor(hasTestTag("reader-panel-sheet")))
                    // On short windows the first scroll expands the sheet before scrolling the list itself.
                    repeat(4) {
                        if (onAllNodesWithTag("reader-options-fullscreen").fetchSemanticsNodes().isEmpty()) {
                            optionsList.performTouchInput { swipeUp() }
                            waitForIdle()
                        }
                    }
                    snapshot("fullscreen-options")
                    onNodeWithTag("reader-options-fullscreen").assertIsDisplayed().performClick(); waitForIdle()
                }
                idle(); check(fullscreen)
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.F11) }; idle(); check(!fullscreen)
                onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.pageIndex == 3 && !controller.state.value.busy
                }; idle()
                val sliderProgress = runBlocking { session.library.progress(content.id) }
                check(sliderProgress?.page == 3)
                onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                val bookmark = controller.state.value.bookmarks.single()
                check(bookmark.page == 3 && bookmark.pageId == controller.state.value.pages[3].id)
                check(runBlocking { session.library.progress(content.id) } == sliderProgress)
                onNodeWithTag("reader-bookmark-toggle").performTouchInput { longClick() }; idle()
                snapshot("bookmarks")
                onNodeWithTag("reader-panel-close").performClick(); idle()
                onNodeWithTag("reader-options").performClick(); idle()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isNotEmpty() }
                snapshot("options")
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.Escape) }; idle()
                onNodeWithTag("reader-chapters").performClick(); idle()
                onNodeWithTag("reader-chapter-query").performTextInput("H")
                onNodeWithTag("reader-chapter-query").performKeyInput { pressKey(Key.H) }
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.pageIndex == 3)
                onNodeWithTag("reader-chapter-query").performTextClearance()
                snapshot("chapters")
                val next = controller.state.value.adjacentChapter(true)!!
                onNodeWithTag("reader-chapter:${next.id}").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.chapter?.id == next.id && !controller.state.value.busy
                }; idle()
                onNodeWithTag("reader-panel").assertDoesNotExist()
                check(controller.state.value.pageIndex == 0)
                onNodeWithTag("reader-bookmark-toggle").performTouchInput { longClick() }; idle()
                onNodeWithTag("reader-bookmark:${bookmark.pageId}").performClick(); idle()
                check(controller.state.value.chapter?.id == bookmark.chapterId && controller.state.value.pageIndex == 3)
                onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                check(controller.state.value.bookmarks.isEmpty())
                readerKey(Key.F11); idle(); check(fullscreen)
                onNodeWithTag("reader-back").performClick(); idle(); check(!fullscreen)
                onNodeWithTag("tablet-navigation").assertExists()
                onNodeWithTag("preview-favourite").performClick(); idle()
                onNodeWithTag("nav:收藏").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertExists()
                onNodeWithTag("library-query").performTextInput("no-match"); idle()
                onNodeWithTag("content:${content.id}").assertDoesNotExist()
                onNodeWithTag("library-clear-empty").performClick(); idle()
                onNodeWithTag("library-filters").performClick(); idle()
                onNodeWithTag("library-reading:UNREAD").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertDoesNotExist()
                snapshot("library-filter")
                onNodeWithTag("library-filter-reset").performClick(); idle()
                onNodeWithTag("library-filter-scrollbar").assertExists()
                // Categories moved from the drawer to Android's tabs rail under the top bar.
                val category = controller.state.value.library.categories.single()
                onNodeWithTag("library-filter-close").performClick(); idle()
                onNodeWithTag("library-category:${category.id}").performClick(); idle()
                check(controller.state.value.librarySelection.categoryId == category.id)
                onNodeWithTag("library-filters").performClick(); idle()
                onNodeWithTag("library-source:${content.source.name}").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.filterCount == 2)
                onNodeWithTag("content:${content.id}").assertExists()
                onNodeWithTag("library-filter-reset").performClick(); idle()
                // Android's tag filter group: seed one tagged work, then narrow the grid down to it.
                val tag = ContentTag("Drama", "drama", content.source)
                val tagged = content.copy(id = content.id + 2000, title = "Tagged shelf", url = "/tagged/shelf",
                    publicUrl = "https://fixture.invalid/tagged/shelf", tags = setOf(tag), chapters = null,
                    sourceData = null, state = "ONGOING")
                // A second work differs only in its publication state, so the state group can narrow the grid.
                val finished = content.copy(id = content.id + 3000, title = "Finished shelf", url = "/finished/shelf",
                    publicUrl = "https://fixture.invalid/finished/shelf", tags = emptySet(), chapters = null,
                    sourceData = null, state = "FINISHED")
                runBlocking { session.library.addFavourite(tagged) }
                runBlocking { session.library.addFavourite(finished) }
                // Android's NEW_CHAPTERS chip reads the update counter the tracker keeps, so seed one row.
                runBlocking {
                    session.storage.database.getTracksDao().upsert(org.skepsun.kototoro.tracker.data.TrackEntity(
                        tagged.id, 0, 2, 0, 0, org.skepsun.kototoro.tracker.data.TrackEntity.RESULT_HAS_UPDATE, null))
                }
                // A read-through work keeps its tracking counter but loses the cover badge (Android's mapper).
                runBlocking {
                    session.storage.database.getTracksDao().upsert(org.skepsun.kototoro.tracker.data.TrackEntity(
                        finished.id, 0, 3, 0, 0, org.skepsun.kototoro.tracker.data.TrackEntity.RESULT_HAS_UPDATE, null))
                    session.storage.database.getHistoryDao()
                        .upsert(org.skepsun.kototoro.history.data.HistoryEntity(
                            finished.id, 0L, 0L, 0L, 0, 0f, 1f, 0L, 1, null))
                }
                runBlocking { controller.library().join() }; idle()
                // The unread counter sits top-right as Android draws it; a completed work keeps the count in the
                // macro but drops the badge, and the history shelf never draws it at all.
                onNodeWithTag("content-new-chapters:${tagged.id}", useUnmergedTree = true).assertExists()
                check(onNodeWithTag("content-new-chapters:${tagged.id}", useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single() ==
                    desktopText("library.newChapters", 2))
                onNodeWithTag("content-new-chapters:${finished.id}", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("library-tags-open").performScrollTo().performClick(); idle()
                onNodeWithTag("library-tags-dialog").assertExists()
                snapshotTag("library-tags-dialog", "library-tag-picker")
                onNodeWithTag("library-tag:drama").performClick(); idle()
                check(controller.state.value.librarySelection.tags == setOf(tag))
                onNodeWithTag("content:${tagged.id}").assertExists()
                onNodeWithTag("content:${content.id}").assertDoesNotExist()
                onNodeWithTag("library-tag-clear").performClick(); idle()
                check(controller.state.value.librarySelection.tags.isEmpty())
                onNodeWithTag("content:${content.id}").assertExists()
                onNodeWithTag("library-tag-done").performClick(); idle()
                onNodeWithTag("library-tags-dialog").assertDoesNotExist()
                // Android's publication-state group offers only the states the shelf holds, OR-ed inside it.
                onNodeWithTag("library-state:FINISHED").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.states == setOf("FINISHED"))
                onNodeWithTag("content:${finished.id}").assertExists()
                onNodeWithTag("content:${tagged.id}").assertDoesNotExist()
                onNodeWithTag("library-state:FINISHED").performClick(); idle()
                check(controller.state.value.librarySelection.states.isEmpty())
                // The grid is lazy, so the header count proves the row set without depending on scroll position.
                fun shownCount(): Int = onNodeWithTag("library-count").fetchSemanticsNode()
                    .config[SemanticsProperties.Text].first().text.substringBefore(' ').toInt()
                check(shownCount() == controller.state.value.library.entries.size)
                // "Downloaded" reads the stored-chapter queue: nothing is on disk here, so the grid empties.
                onNodeWithTag("library-download:DOWNLOADED").performScrollTo().performClick(); idle()
                check(shownCount() == 0)
                onNodeWithTag("library-download:MISSING").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.download == DesktopLibraryDownload.MISSING)
                check(controller.state.value.librarySelection.filterCount == 1)
                check(shownCount() == controller.state.value.library.entries.size)
                // Macros stay Android's independent groups: the favourite pair is meaningless on this shelf,
                // NSFW matches nothing here and NSFW together with SFW intersects to an empty grid.
                onNodeWithTag("library-macro:FAVORITE").assertDoesNotExist()
                onNodeWithTag("library-macro:NOT_FAVORITE").assertDoesNotExist()
                onNodeWithTag("library-macro:NEW_CHAPTERS").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.macros == setOf(DesktopLibraryMacro.NEW_CHAPTERS))
                // Both works carry a counter; only the read-through one loses its badge.
                check(shownCount() == 2)
                onNodeWithTag("content:${tagged.id}").assertExists()
                onNodeWithTag("library-macro:NEW_CHAPTERS").performScrollTo().performClick(); idle()
                onNodeWithTag("library-macro:NSFW").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.macros == setOf(DesktopLibraryMacro.NSFW))
                check(shownCount() == 0)
                onNodeWithTag("library-macro:SFW").performScrollTo().performClick(); idle()
                check(shownCount() == 0)
                onNodeWithTag("library-macro:NSFW").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.macros == setOf(DesktopLibraryMacro.SFW))
                check(shownCount() == controller.state.value.library.entries.size)
                check(controller.state.value.librarySelection.filterCount == 2)
                snapshot("library-filter-groups")
                // Drop the fake track rows so the feed checks below still see an empty update list.
                runBlocking {
                    session.storage.database.getTracksDao().delete(tagged.id)
                    session.storage.database.getTracksDao().delete(finished.id)
                }
                runBlocking { controller.library().join() }; idle()
                onNodeWithTag("library-filter-reset").performClick(); idle()
                check(controller.state.value.librarySelection.filterCount == 0)
                onNodeWithTag("library-filter-close").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertExists()
                // Android edits categories on their own screen; the desktop does it in a dialog over the grid.
                onNodeWithTag("library-categories-manage").performClick(); idle()
                onNodeWithTag("category-dialog").assertExists()
                onNodeWithTag("category-new-field").performTextInput("新刊"); idle()
                onNodeWithTag("category-new-add").performClick(); idle()
                val created = controller.state.value.library.categories.last()
                check(created.title == "新刊" && created.id != category.id)
                onNodeWithTag("library-category:${created.id}").assertExists()
                onNodeWithTag("category-rename:${created.id}").performClick(); idle()
                onNodeWithTag("category-edit-field:${created.id}").performTextClearance()
                onNodeWithTag("category-edit-field:${created.id}").performTextInput("待读"); idle()
                onNodeWithTag("category-edit-save:${created.id}").performClick(); idle()
                check(controller.state.value.library.categories.last().title == "待读")
                onNodeWithTag("category-up:${created.id}").performClick(); idle()
                check(controller.state.value.library.categories.first().id == created.id)
                snapshotTag("category-dialog", "library-categories")
                onNodeWithTag("category-delete:${created.id}").performClick(); idle()
                check(controller.state.value.library.categories.map { it.id } == listOf(category.id))
                onNodeWithTag("library-category:${created.id}").assertDoesNotExist()
                onNodeWithTag("category-dialog-close").performClick(); idle()
                onNodeWithTag("category-dialog").assertDoesNotExist()
                val beforeQuery = System.getProperty("fixture.reader.requests")
                onNodeWithTag("library-query").performTextInput(content.title); idle()
                val librarySelection = controller.state.value.librarySelection
                onNodeWithTag("content:${content.id}").performClick(); idle()
                check(controller.state.value.detailsOrigin == DesktopScreen.LIBRARY)
                onNodeWithTag("details-close").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.LIBRARY)
                check(controller.state.value.librarySelection == librarySelection)
                check(onNodeWithTag("library-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text ==
                    content.title)
                onNodeWithTag("nav:历史").performClick(); idle()
                check(onNodeWithTag("library-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "")
                onNodeWithTag("content:${content.id}").performClick(); idle()
                check(controller.state.value.detailsOrigin == DesktopScreen.HISTORY)
                onNodeWithTag("details-close").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.HISTORY)
                onNodeWithTag("nav:收藏").performClick(); idle()
                check(onNodeWithTag("library-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == content.title)
                check(System.getProperty("fixture.reader.requests") == beforeQuery)
                snapshot("library")
                runBlocking {
                    repeat(36) { index ->
                        session.library.addFavourite(content.copy(id = content.id + 1000 + index,
                            title = "Authored shelf $index", url = "/authored/shelf/$index",
                            publicUrl = "https://fixture.invalid/authored/shelf/$index", chapters = null, sourceData = null))
                    }
                }
                controller.librarySelection(DesktopLibrarySelection())
                runBlocking { controller.library().join() }; idle()
                val target = controller.state.value.library.entries.indexOfFirst { it.content.id == content.id }
                check(target >= 36)
                onNodeWithTag("content-grid").performScrollToIndex(target); idle()
                val scrolledCard = onNodeWithTag("content:${content.id}").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("content:${content.id}").performClick(); idle()
                onNodeWithTag("details-close").performClick(); idle()
                check(onNodeWithTag("content:${content.id}").fetchSemanticsNode().boundsInRoot == scrolledCard)
                snapshot("scrolled-library")

                // Subscriptions: Android's tracker rules over the shared tables, on the shared feed components.
                onNodeWithTag("nav:订阅").performClick(); idle()
                onNodeWithTag("feed-panel").assertExists()
                check(controller.state.value.trackedCategories == 1) { "the favourites category tracks updates" }
                onNodeWithTag("feed-tracking-off").assertDoesNotExist()
                onNodeWithTag("feed-check").performClick(); idle()
                val chapters = runBlocking { session.library.find(content.id) }?.chapters.orEmpty()
                check(chapters.size >= 2) { "the reader fixture has several chapters" }
                runBlocking {
                    // Pretend the last check saw only the first chapter, so the rest are new.
                    val track = requireNotNull(session.storage.database.getTracksDao().find(content.id))
                    session.storage.database.getTracksDao().upsert(org.skepsun.kototoro.tracker.data.TrackEntity(
                        content.id, chapters.first().id, 0, track.lastCheckTime, track.lastChapterDate,
                        track.lastResult, null))
                }
                onNodeWithTag("feed-check").performClick(); idle()
                val update = requireNotNull(controller.state.value.feed.updateRowsByOwnerId[content.id])
                check(update.newChapters == chapters.size - 1) { "new chapters: ${update.newChapters}" }
                onNodeWithTag("feed-updated:${content.id}").assertExists()
                onNodeWithTag("nav-feed-badge", useUnmergedTree = true).assertExists()
                val log = controller.state.value.feed.rows.first { it.anchorMangaId == content.id }
                onNodeWithTag("feed-row:${log.logId}").assertExists()
                snapshot("feed")
                onNodeWithTag("nav:主页").performClick(); idle()
                onNodeWithTag("home-panel").assertExists()
                onNodeWithTag("home-hero").assertExists()
                onNodeWithTag("home-history:${content.id}").assertExists()
                onNodeWithTag("home-update:${content.id}").assertExists()
                // The shared Android layout lazily composes quick access below the highlights on narrow panes.
                onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("home-quick-access"))
                onNodeWithTag("home-quick-access").assertIsDisplayed()
                onNodeWithTag("home-scroll").performScrollToIndex(0)
                // Recommendations: Android's suggestion rules over the installed fixture source.
                // Suggestions are off by default, as on Android; the home button enables and generates them.
                check(!controller.state.value.suggestionSettings.enabled)
                onNodeWithText("开启推荐").assertExists()
                onNodeWithTag("home-recommendations-generate").performScrollTo().performClick(); idle()
                check(controller.state.value.suggestionSettings.enabled)
                check((session.storage.preferences.open("desktop_background").snapshot()["suggestions"]
                    as? org.skepsun.kototoro.core.source.SourcePreferenceValue.Toggle)?.value == true)
                val suggested = controller.state.value.suggestions
                check(suggested.isNotEmpty()) { "the fixture source yields suggestions" }
                check(runBlocking { session.storage.database.getSuggestionDao().count() } == suggested.size)
                onNodeWithTag("home-recommendation:${suggested.first().id}").assertExists()
                onNodeWithTag("home-recommendations-empty").assertDoesNotExist()
                snapshot("home")
                onNodeWithTag("home-update:${content.id}").performScrollTo().performClick(); idle()
                check(controller.state.value.detailsOrigin == DesktopScreen.HOME)
                check(controller.state.value.content?.id == content.id)
                runBlocking { controller.dismissDetails().join() }; idle()
                check(controller.state.value.screen == DesktopScreen.HOME)
                check(controller.state.value.feed.updateRowsByOwnerId.isEmpty()) { "opening an update marks it read" }
                onNodeWithTag("home-update:${content.id}").assertDoesNotExist()
                onNodeWithTag("nav-feed-badge", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("home-quick:0").performScrollTo().performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.LIBRARY)

                // Settings: Android's tracker frequency is a choice dialog, and the recommendation rules live on their
                // own screen instead of being squashed into the "more" page as one row of chips per source.
                onNodeWithTag("nav:更多").performClick(); idle()
                snapshot("more")
                // Android's reader settings page: the same preferences the reader panel edits, reachable without
                // opening a chapter and edited through one dialog per value instead of chips in the panel.
                onNodeWithTag("reader-settings-open").performScrollTo().performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.READER_SETTINGS)
                snapshot("reader-settings")
                onNodeWithTag("reader-setting-mode").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-mode:DOUBLE").performClick(); idle()
                check(controller.state.value.readerSettings.mode == DesktopReaderMode.DOUBLE)
                onNodeWithTag("reader-setting-direction").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-direction:RTL").performClick(); idle()
                check(controller.state.value.readerSettings.rightToLeft && !controller.state.value.readerSettings.vertical)
                onNodeWithTag("reader-setting-fit").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-fit:FIT_WIDTH").performClick(); idle()
                check(controller.state.value.readerSettings.fitMode ==
                    org.skepsun.kototoro.reader.core.ZoomMode.FIT_WIDTH)
                onNodeWithTag("reader-setting-background").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-background:BLACK").performClick(); idle()
                check(controller.state.value.readerSettings.background == DesktopReaderBackground.BLACK)
                onNodeWithTag("reader-setting-page-numbers").performScrollTo().performClick(); idle()
                check(controller.state.value.readerSettings.pageNumbers)
                onNodeWithTag("reader-setting-auto-chapter").performScrollTo().performClick(); idle()
                check(controller.state.value.readerSettings.automaticChapter)
                onNodeWithTag("reader-setting-animation").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-animation:NONE").performClick(); idle()
                check(controller.state.value.readerSettings.animation ==
                    org.skepsun.kototoro.core.prefs.ReaderAnimation.NONE)
                onNodeWithTag("reader-settings-back").performScrollTo().performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.MORE)
                onNodeWithTag("settings-tracker-frequency").performScrollTo().performClick(); idle()
                onNodeWithTag("settings-tracker-frequency:2.0").performClick(); idle()
                check(controller.state.value.trackerSettings.frequency == 2f)
                check((session.storage.preferences.open("desktop_background").snapshot()["tracker_freq"]
                    as? org.skepsun.kototoro.core.source.SourcePreferenceValue.Text)?.value == "2.0")
                onNodeWithTag("settings-tracker-dialog").assertDoesNotExist()
                onNodeWithTag("settings-recommendations-open").performScrollTo().performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.RECOMMENDATIONS)
                onNodeWithTag("settings-suggestions-enabled").assertExists()
                onNodeWithTag("settings-suggestions-exclude-tags").assertExists()
                snapshot("recommendations")
                val sourceName = controller.state.value.sources.first().source.name
                onNodeWithTag("settings-suggestions-prefer-sources").performScrollTo().performClick(); idle()
                onNodeWithTag("settings-suggestions-sources-dialog").assertExists()
                onNodeWithTag("settings-suggestions-prefer:$sourceName").performClick(); idle()
                check(sourceName in controller.state.value.suggestionSettings.preferredSources)
                onNodeWithTag("settings-suggestions-sources-dialog-close").performClick(); idle()
                onNodeWithTag("settings-suggestions-sources-dialog").assertDoesNotExist()
                onNodeWithTag("settings-suggestions-prefer:$sourceName").assertDoesNotExist()
                onNodeWithTag("nav:更多").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.MORE)
                check(runBlocking { controller.runDueBackgroundWork() }.isEmpty()) { "both jobs just ran manually" }
                val later = System.currentTimeMillis() + 10 * 3_600_000L
                check(runBlocking { controller.runDueBackgroundWork(later) } ==
                    setOf(DesktopBackgroundTask.TRACKER, DesktopBackgroundTask.SUGGESTIONS))
                check(controller.state.value.suggestions.isNotEmpty())
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
