package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopTriState
import org.skepsun.kototoro.tracker.data.TrackEntity
import java.nio.file.Path

/**
 * The updates destination against a real library: a favourited work whose track carries new chapters, entered the way a
 * mouse reaches it (the subscriptions carousel's "all", because the rail is already at Android's five-item limit).
 *
 * The rows are seeded through the tracks table instead of a tracker run: the page's contract is "whatever
 * `chapters_new` holds", the check itself is covered by `DesktopTrackerTest`, and a seeded row keeps this probe
 * independent of the fixture source's network behaviour.
 */
internal object DesktopUpdatesProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun seed(workId: Long, newChapters: Int) = runBlocking {
                    val now = System.currentTimeMillis()
                    session.storage.database.getTracksDao().upsert(TrackEntity(
                        mangaId = workId,
                        lastChapterId = 1L,
                        newChapters = newChapters,
                        lastCheckTime = now,
                        lastChapterDate = now - 3_600_000L,
                        lastResult = TrackEntity.RESULT_HAS_UPDATE,
                        lastError = null,
                    ))
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick(); idle()
                onNodeWithTag("content:${controller.state.value.items.single().id}").performClick(); idle()
                val work = requireNotNull(controller.state.value.content) { "the details page did not load" }
                // Android only tracks favourites, and the tracks table hangs off the manga row the favourite writes.
                runBlocking { session.library.addFavourite(work) }
                seed(work.id, newChapters = 2)
                runBlocking { controller.subscriptions().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.FEED }
                idle()
                // The subscriptions page shows the same works in its carousel, and its "all" is the entry used here.
                onNodeWithTag("feed-updated:${work.id}").assertExists()
                onNodeWithTag("feed-updated-more").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.UPDATED }
                idle()
                onNodeWithTag("updates-panel").assertExists()
                onNodeWithTag("update-row:${work.id}").assertExists()
                // Android's header card: "N works updated · M new chapters".
                onNodeWithText(AndroidStrings.format("updated_header_summary", 1, 2), substring = true).assertExists()
                onNodeWithTag("update-chapters:${work.id}", useUnmergedTree = true)
                    .assertTextContains(desktopText("common.newChapters", 2), substring = true)
                // Opening a card is Android's `openTracked`: it acknowledges the notice (logs read) and shows the work.
                // The counter itself survives, because Android only clears it when the new chapters are read to the end.
                onNodeWithTag("update-row:${work.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                check(runBlocking { session.storage.database.getTracksDao().find(work.id)?.newChapters } == 2) {
                    "opening an update must not drain the counter Android clears on reading"
                }
                runBlocking { controller.dismissDetails().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.UPDATED }
                idle()
                onNodeWithTag("update-row:${work.id}").assertExists()
                // A row's 移除 is Android's REMOVE (`clearUpdates`): counter drained, history and progress kept.
                val progress = runBlocking { session.library.progress(work.id) }
                onNodeWithTag("update-read:${work.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.feed.updateRowsByOwnerId.isEmpty() }
                idle()
                onNodeWithTag("updates-empty").assertExists()
                check(runBlocking { session.storage.database.getTracksDao().find(work.id)?.newChapters } == 0)
                check(runBlocking { session.library.progress(work.id) } == progress) { "only the reminder goes" }
                // The header's 全部标为已读 does the same for every work at once.
                seed(work.id, newChapters = 3)
                runBlocking { controller.showUpdates().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.feed.updateRowsByOwnerId.isNotEmpty() }
                idle()
                onNodeWithTag("updates-mark-all-read").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.feed.updateRowsByOwnerId.isEmpty() }
                idle()
                check(runBlocking { session.storage.database.getTracksDao().find(work.id)?.newChapters } == 0)
                // Android's updates card also carries 继续阅读, which opens the next unread chapter.
                seed(work.id, newChapters = 1)
                runBlocking { controller.showUpdates().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.feed.updateRowsByOwnerId.isNotEmpty() }
                idle()
                // The fixture work is adult, and the default asks first; answer for good so 继续阅读 opens directly.
                controller.setIncognitoForNsfw(DesktopTriState.DISABLED)
                onNodeWithTag("update-continue:${work.id}").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == DesktopScreen.READER }
                idle()
                checkNotNull(controller.state.value.chapter) { "继续阅读 must open a chapter" }
                check(controller.state.value.pages.isNotEmpty()) { "the chapter's pages must load" }
                check(controller.state.value.savesProgress) { "a plain 继续阅读 records the history" }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=$mode")
    }
}
