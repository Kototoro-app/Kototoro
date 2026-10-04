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
                    val chapter = controller.state.value.content!!.chapters!!.first { it.number == 1f }
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
                onNodeWithTag("reader-viewport").performTouchInput { click() }
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.MoveEnd) }
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.pageIndex == 4 }
                idle()
                onNodeWithTag("reader-progress").assertTextEquals("5 / 5")
                val record = controller.downloads.state.value.single().record
                check(runBlocking { session.library.progress(record.contentId) }?.page == 4)
                check(System.getProperty("fixture.reader.requests", "0") == imageCalls)
                check(System.getProperty("fixture.reader.page_lists", "0") == pageCalls)
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
