package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** Both page metadata and image clients go offline for the actual downloaded reader. */
internal object DesktopDownloadsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        if (mode.endsWith("read")) {
            System.setProperty("fixture.reader.offline", "true")
            System.setProperty("fixture.reader.pages.offline", "true")
        }
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            // This probe drives the manual "reclaim" flow, so the automatic sweep (which runs as soon as the queue
            // goes idle) is switched off first and turned back on at the end to prove the toggle works.
            if (mode.endsWith("write")) controller.setAutoDownloadCleanup(false)
            if (mode.endsWith("write")) runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = if (mode.endsWith("read")) 920 else 1260,
                height = if (mode.endsWith("read")) 620 else 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun status(expected: DesktopDownloadStatus) {
                    waitUntil(timeoutMillis = 15_000) { controller.downloads.state.value.singleOrNull()?.status == expected }
                    waitForIdle()
                }
                fun snapshot(name: String) {
                    val bitmap = (if (name == "cleanup-preview") onNodeWithTag("download-cleanup-dialog")
                        else onRoot()).captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image -> image.encodeToData()?.use {
                        Files.write(Path.of(args[3]).resolve("$mode-$name.png"), it.bytes)
                    } }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                idle()
                if (mode.endsWith("write")) {
                    onNodeWithTag("source:MIHON_9007199254740995").performClick()
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.items.size == 1 }
                    val content = controller.state.value.items.single()
                    onNodeWithTag("content:${content.id}").performClick()
                    idle()
                    onNodeWithTag("details-expand").performClick(); idle()
                    onNodeWithTag("details-full").assertExists()
                    val chapter = controller.state.value.content!!.chapters!!.first { it.number == 1f }
                    // Android's chapter long-press sheet, reached through the card's overflow menu on a mouse.
                    onNodeWithTag("chapter-menu:${chapter.id}").performClick(); idle()
                    onNodeWithTag("details-mark-read:${chapter.id}").performClick(); idle()
                    check(runBlocking { session.library.progress(content.id) }?.chapterId == chapter.id)
                    check(controller.state.value.detailsChapterId == chapter.id)
                    onNodeWithTag("chapter-menu:${chapter.id}").performClick(); idle()
                    // The first chapter has nothing before it, so "unread" clears the record instead of pointing at it.
                    onNodeWithTag("details-mark-unread:${chapter.id}").performClick(); idle()
                    check(runBlocking { session.library.progress(content.id) } == null)
                    check(controller.state.value.detailsChapterId == null)
                    // Android's batch mode: a select toggle replaces navigation, then one bar acts on every row.
                    val second = controller.state.value.content!!.chapters!!.first { it.number == 2f }
                    onNodeWithTag("details-chapter-select").performClick(); idle()
                    onNodeWithTag("read-chapter:${chapter.id}").performClick(); idle()
                    onNodeWithTag("read-chapter:${second.id}").performClick(); idle()
                    onNodeWithTag("details-selection-bar").assertExists()
                    onNodeWithTag("details-selection-count").assertExists()
                    // Android hides the per-row buttons while selecting, so only the batch bar offers commands.
                    onNodeWithTag("chapter-menu:${chapter.id}").assertDoesNotExist()
                    onNodeWithTag("download-chapter:${chapter.id}").assertDoesNotExist()
                    snapshot("chapter-selection")
                    // A batch is still one reading position: the last chapter of the selection wins.
                    onNodeWithTag("details-selection-read").performClick(); idle()
                    check(runBlocking { session.library.progress(content.id) }?.chapterId == second.id)
                    check(controller.state.value.detailsChapterId == second.id)
                    onNodeWithTag("details-selection-all").performClick(); idle()
                    snapshot("chapter-selection-all")
                    onNodeWithTag("details-selection-clear").performClick(); idle()
                    // Leaving the mode hands clicks back to navigation, and the download steps below start clean.
                    onNodeWithTag("details-chapter-select").performClick(); idle()
                    controller.markChapterUnread(chapter); idle()
                    check(runBlocking { session.library.progress(content.id) } == null)
                    // Android's details heart toggles: the same button must also take the work off the shelf.
                    onNodeWithTag("preview-favourite").performClick(); idle()
                    check(controller.state.value.isFavourite)
                    check(runBlocking { session.library.isFavourite(content.id) })
                    onNodeWithTag("preview-favourite").performClick(); idle()
                    check(!controller.state.value.isFavourite)
                    check(!runBlocking { session.library.isFavourite(content.id) })
                    System.setProperty("fixture.reader.failure.index", "14")
                    onNodeWithTag("download-chapter:${chapter.id}").performClick()
                    status(DesktopDownloadStatus.FAILED)
                    check(controller.downloads.state.value.single().record.completedPages == 2)
                    check(runBlocking { session.library.progress(content.id) } == null)
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:下载").performClick()
                    idle()
                    val key = controller.downloads.state.value.single().key
                    snapshot("failed")
                    System.clearProperty("fixture.reader.failure.index")
                    System.setProperty("fixture.reader.delay.index", "14")
                    onNodeWithTag("download-resume:$key").performClick()
                    waitUntil(timeoutMillis = 15_000) { System.getProperty("fixture.reader.waiting") == "true" }
                    onNodeWithTag("download-pause:$key").performClick()
                    status(DesktopDownloadStatus.PAUSED)
                    waitUntil(timeoutMillis = 15_000) { System.getProperty("fixture.reader.waiting") == null }
                    check(session.downloadStore.records.value.single().completedPages == 2)
                    check(runBlocking { session.library.progress(content.id) } == null)
                    snapshot("paused")
                    System.clearProperty("fixture.reader.delay.index")
                    onNodeWithTag("download-resume:$key").performClick()
                    status(DesktopDownloadStatus.COMPLETE)
                    check(controller.downloads.state.value.single().record.completedPages == 5)
                    check(System.getProperty("fixture.reader.requests") == "7")
                    check(System.getProperty("fixture.reader.page_lists") == "1")
                    check(runBlocking { session.library.progress(content.id) } == null)
                    onNodeWithTag("download-storage").performClick()
                    idle()
                    val plan = requireNotNull(controller.cleanupPreview.value)
                    check(plan.candidates.isNotEmpty())
                    snapshot("cleanup-preview")
                    onNodeWithTag("download-cleanup-cancel").performClick()
                    waitForIdle()
                    check(session.downloadStore.previewCleanup(session.storage.preferences).candidates == plan.candidates)
                    onNodeWithTag("download-storage").performClick()
                    idle()
                    onNodeWithTag("download-cleanup-confirm").performClick()
                    idle()
                    check(session.downloadStore.previewCleanup(session.storage.preferences).candidates.isEmpty())
                    check(controller.downloads.state.value.single().status == DesktopDownloadStatus.COMPLETE)
                    check(runBlocking { session.library.progress(content.id) } == null)
                    // The switch round-trips through the preference store and back to the default.
                    onNodeWithTag("download-auto-cleanup").performClick()
                    idle()
                    check(controller.autoCleanupDownloads.value)
                    check(session.downloadMaintenance.automatic)
                    System.setProperty("fixture.reader.offline", "true")
                    System.setProperty("fixture.reader.pages.offline", "true")
                    snapshot("complete")
                } else {
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:下载").performClick()
                    idle()
                    status(DesktopDownloadStatus.COMPLETE)
                }
                val key = controller.downloads.state.value.single().key
                val imageCalls = System.getProperty("fixture.reader.requests", "0")
                val pageCalls = System.getProperty("fixture.reader.page_lists", "0")
                onNodeWithTag("download-read:$key").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy &&
                    controller.state.value.screen == DesktopScreen.READER && controller.state.value.image != null }
                idle()
                onNodeWithTag("reader-viewport").requestFocus()
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.MoveEnd) }
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.pageIndex == 4 }
                idle()
                assertReaderProgress("5 / 5")
                val record = controller.downloads.state.value.single().record
                check(runBlocking { session.library.progress(record.contentId) }?.page == 4)
                // The downloaded chapter reads from its files; only the next (not downloaded) chapter, preloaded near the
                // end, may ask the source: one page list and its first pages.
                waitUntil(timeoutMillis = 15_000) { !controller.isPreloadingChapter }
                check(System.getProperty("fixture.reader.page_lists", "0").toInt() - pageCalls.toInt() <= 1)
                check(System.getProperty("fixture.reader.requests", "0").toInt() - imageCalls.toInt() <= 2)
                snapshot("offline-last-page")
            }
        } finally {
            System.clearProperty("fixture.reader.failure.index")
            System.clearProperty("fixture.reader.delay.index")
            System.clearProperty("fixture.reader.pages.offline")
            System.clearProperty("fixture.reader.offline")
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
