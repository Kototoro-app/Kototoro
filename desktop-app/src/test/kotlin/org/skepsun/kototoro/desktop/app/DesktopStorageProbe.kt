package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorProbeState
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopStorageCategory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Android's storage and network page over the real UI and the real data root. Files are written into the image and log
 * directories first, so the figures the page shows are the walk's, not a stub's: the page must attribute them to the
 * right slices and still offer the proxy sub-screen Android reaches from the same hub.
 */
internal object DesktopStorageProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        val root = session.storage.paths
        Files.createDirectories(root.images.resolve("probe"))
        Files.write(root.images.resolve("probe/page.img"), ByteArray(3000))
        Files.createDirectories(root.logs)
        Files.write(root.logs.resolve("source-errors.log"), ByteArray(100))
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
                // A Dialog is its own root, so onRoot() is ambiguous while one is open: capture the dialog instead.
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
                // "更多" no longer carries the proxy row: Android reaches it from the storage and network hub.
                onNodeWithTag("proxy-settings-open").assertDoesNotExist()
                onNodeWithTag("storage-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.STORAGE }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.storageUsage != null }
                idle()

                onNodeWithTag("storage-usage-chart").assertExists()
                onNodeWithTag("storage-usage-summary").assertExists()
                // The breakdown rows are the shared ones Android's legend draws, and so is the totals row.
                snapshot("usage")
                onNodeWithTag("storage-usage:IMAGES").assertExists()
                onNodeWithTag("storage-usage:LOGS").assertExists()
                // The volume's free space is always there, even when this copy holds nothing.
                onNodeWithTag("storage-usage:AVAILABLE").assertExists()

                val usage = controller.state.value.storageUsage!!
                check(usage.find(DesktopStorageCategory.IMAGES)!!.bytes >= 3000L) { "images: ${usage.items}" }
                check(usage.find(DesktopStorageCategory.LOGS)!!.bytes >= 100L) { "logs: ${usage.items}" }
                check(usage.usedBytes >= 3100L) { "used: ${usage.usedBytes}" }
                check(usage.availableBytes > 0L) { "available: ${usage.availableBytes}" }
                check(usage.volumeBytes >= usage.availableBytes) { "volume: ${usage.volumeBytes}" }

                // The proxy row is the hub's own entry to Android's proxy page, with the current route in front.
                onNodeWithTag("proxy-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.PROXY_SETTINGS }
                idle()
                onNodeWithTag("proxy-settings-back").performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.STORAGE) { "the proxy page returns to the hub" }

                // Android's GitHub mirror lives on this hub too: the dialog lists the catalog and the pick is persisted
                // under Android's own key, which is what the download paths read.
                onNodeWithTag("github-mirror-open").performScrollTo().assertExists()
                check(controller.state.value.githubMirror.entry.id == GitHubMirrorCatalog.NATIVE.id) {
                    "mirror default: ${controller.state.value.githubMirror}"
                }
                onNodeWithTag("github-mirror-open").performClick()
                onNodeWithTag("github-mirror-dialog").assertExists()
                onNodeWithTag("github-mirror-option:native").performScrollTo().assertExists()
                onNodeWithTag("github-mirror-option:jsdelivr").assertDoesNotExist()
                onNodeWithTag("github-mirror-option:gh_proxy_com").performScrollTo().performClick()
                idle()
                onNodeWithTag("github-mirror-dialog").assertDoesNotExist()
                check(controller.state.value.githubMirror.entry.id == "gh_proxy_com") {
                    "mirror: ${controller.state.value.githubMirror}"
                }
                check(session.githubMirror.rewrite("https://github.com/o/r/releases/download/v1/a.zip") ==
                    "https://gh-proxy.com/https://github.com/o/r/releases/download/v1/a.zip") { "the rewriter follows" }
                // The saved store is Android's key and value shape, so the choice survives a restart.
                val stored = session.storage.preferences.open("desktop_network").snapshot()["github_mirror"]
                check(stored?.let { it as? SourcePreferenceValue.Text }?.value == "gh_proxy_com") { "stored: $stored" }

                // Android's speed sweep sits next to the choice: starting it is a job the page can cancel again, and the
                // sweep's rows are the runtime's, so the state the page shows comes from the same flow the dialog reads.
                onNodeWithTag("github-mirror-probe").performScrollTo().assertExists()
                onNodeWithTag("github-mirror-probe-start").performClick()
                // This sweep talks to the real mirrors, so it can be finished before the next frame. "Started" is
                // therefore any state other than the idle one it began in; the socket abort itself is covered
                // deterministically by the runtime test `cancelling a sweep aborts its sockets and goes idle`.
                waitUntil(timeoutMillis = 10_000) {
                    session.githubMirror.probeState.value !is GitHubMirrorProbeState.Idle
                }
                idle()
                if (session.githubMirror.probeState.value is GitHubMirrorProbeState.Running) {
                    // The rows are disabled while a page operation is in flight, and a click on a disabled button is a
                    // silent no-op, so the button must be enabled before it means anything.
                    onNodeWithTag("github-mirror-probe-cancel").assertIsEnabled()
                    onNodeWithTag("github-mirror-probe-cancel").performClick()
                }
                waitUntil(timeoutMillis = 10_000) {
                    session.githubMirror.probeState.value !is GitHubMirrorProbeState.Running
                }
                idle()
                check(session.githubMirror.probeResults.value.isNotEmpty()) {
                    "results: ${session.githubMirror.probeResults.value}"
                }
                onNodeWithTag("github-mirror-probe-start").assertExists()

                // Android's data-removal page, one press below the hub. Its rows act on this data root: the image sweep
                // frees the unprotected blob the probe wrote, the reader index row drops the record seeded into the
                // index store, and the feed row opens the same confirmation the feed menu shows.
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
                val opened = controller.state.value
                check(opened.readerIndexCount == 1) { "index: ${opened.readerIndexCount}" }
                check(Files.exists(sweepTarget)) { "the sweep target is written" }
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

                onNodeWithTag("storage-settings-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.MORE) { "the hub's back returns to more" }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
