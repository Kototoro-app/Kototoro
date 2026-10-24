package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopStorageCategory
import java.nio.file.Files
import java.nio.file.Path

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
                onNodeWithTag("proxy-settings-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.STORAGE) { "the proxy page returns to the hub" }
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
