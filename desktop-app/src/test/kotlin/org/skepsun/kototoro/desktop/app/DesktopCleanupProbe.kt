package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * The data removal page over the real UI and the real data root, entered from the storage hub. Its rows act on this
 * copy: the image sweep frees the unprotected blob the probe writes, the page-index row drops the record seeded into
 * the reader index store, the feed row opens the confirmation the feed menu shows, and the read-chapter row is greyed
 * out until something is downloaded while the switch that arms the same sweep at every start persists.
 *
 * The page body is the shared `DataCleanupContent`, so this probe exists separately from `DesktopStorageProbe`: the
 * storage hub's network section sweeps 28 real GitHub mirrors and needs egress that a CI machine may not have, while
 * this page is entirely local.
 */
internal object DesktopCleanupProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        val root = session.storage.paths
        Files.createDirectories(root.images.resolve("probe"))
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
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
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("storage-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.STORAGE }
                idle()

                // The blob the sweep must free, old enough to be past the budget's grace period, plus one reader index
                // record so the page-index row has something to drop.
                val sweepTarget = root.images.resolve("probe-cleaned.img")
                Files.write(sweepTarget, ByteArray(2048))
                Files.setLastModifiedTime(sweepTarget, FileTime.fromMillis(System.currentTimeMillis() - 600_000))
                val readerStore = session.storage.preferences.open("desktop_reader_images")
                readerStore.edit(SourcePreferenceEdit(changes = mapOf(
                    "probe-page" to SourcePreferenceValue.Text("{}"),
                )))
                onNodeWithTag("cleanup-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DATA_CLEANUP }
                idle()
                check(controller.state.value.readerIndexCount == 1) {
                    "index: ${controller.state.value.readerIndexCount}"
                }
                check(Files.exists(sweepTarget)) { "the sweep target is written" }
                // Three of Android's groups are on the page (its local-storage group has no Windows counterpart), each
                // with the figures this data root reports.
                onNodeWithTag("cleanup-image-cache").assertExists()
                onNodeWithTag("cleanup-page-index").assertExists()
                onNodeWithTag("cleanup-feed").assertExists()
                onNodeWithTag("cleanup-read-chapters").assertExists()
                onNodeWithTag("cleanup-read-chapters-auto").assertExists()
                snapshot("data-cleanup")

                onNodeWithTag("cleanup-image-cache").performClick()
                idle()
                check(!Files.exists(sweepTarget)) { "the cache row deletes the unprotected blob" }
                check(controller.state.value.imageCache.usedBytes == 0L) {
                    "cache: ${controller.state.value.imageCache}"
                }

                onNodeWithTag("cleanup-page-index").performClick()
                idle()
                check(controller.state.value.readerIndexCount == 0) {
                    "index: ${controller.state.value.readerIndexCount}"
                }
                check(readerStore.snapshot().isEmpty()) { "store: ${readerStore.snapshot()}" }
                check(controller.state.value.message == desktopText("cleanup.pagesCleared", 1)) {
                    "message: ${controller.state.value.message}"
                }

                onNodeWithTag("cleanup-feed").performScrollTo().performClick()
                onNodeWithTag("feed-clear-dialog").assertExists()
                snapshotTag("feed-clear-dialog", "data-cleanup-feed-dialog")
                onNodeWithTag("feed-clear-cancel").performClick()
                idle()
                onNodeWithTag("feed-clear-dialog").assertDoesNotExist()

                // Android's chapter group: nothing is saved in this data root, so the action row is greyed out (there is
                // nothing to delete) while the switch that arms the same sweep at every start still works and persists.
                onNodeWithTag("cleanup-read-chapters").performScrollTo().assertIsNotEnabled()
                check(!controller.state.value.chaptersClearAuto) { "the switch starts off, like Android" }
                onNodeWithTag("cleanup-read-chapters-auto").performScrollTo().performClick()
                idle()
                check(controller.state.value.chaptersClearAuto) { "the switch turns on" }
                val armed = session.storage.preferences.open("desktop_cleanup").snapshot()["chapters_clear_auto"]
                check((armed as? SourcePreferenceValue.Toggle)?.value == true) { "stored: $armed" }
                snapshot("data-cleanup-chapters")

                onNodeWithTag("data-cleanup-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.STORAGE) { "the cleanup page returns to the hub" }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
