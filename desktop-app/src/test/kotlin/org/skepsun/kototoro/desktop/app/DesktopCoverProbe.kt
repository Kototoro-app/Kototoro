package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path

internal object DesktopCoverProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        if (mode == "cover-read") System.setProperty("fixture.cover.offline", "yes")
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "cover-write") runBlocking { controller.importJar(Path.of(args[7])).join() }
            check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
            var primary: SourceContent? = null
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun settled() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun cover(content: SourceContent) {
                    waitUntil(timeoutMillis = 15_000) {
                        val covers = onAllNodesWithTag("cover:${content.id}", useUnmergedTree = true).fetchSemanticsNodes()
                        val details = controller.state.value.screen == DesktopScreen.DETAILS
                        val preview = onAllNodesWithTag("tablet-preview-content").fetchSemanticsNodes().isNotEmpty()
                        covers.size == if (details && preview) 3 else if (details) 2 else 1
                    }
                }
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    val file = Files.createDirectories(Path.of(args[3])).resolve("$mode-$name.png")
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(file, it.bytes) }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                settled()
                onNodeWithTag("source:MIHON_9007199254740996").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 3 }
                settled()
                check(controller.state.value.descriptor?.isCoverFetchingSupported == true)
                val items = controller.state.value.items
                val first = items.first { it.title.startsWith("primary") }.also { primary = it }
                val fallback = items.first { it.title.startsWith("fallback") }
                val broken = items.first { it.title.startsWith("broken") }
                cover(first)
                cover(fallback)
                if (mode == "cover-write") {
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("cover-retry:${broken.id}", useUnmergedTree = true)
                            .fetchSemanticsNodes().size == 1
                    }
                    System.setProperty("fixture.cover.repaired", "yes")
                    onNodeWithTag("cover-retry:${broken.id}", useUnmergedTree = true).performClick()
                    cover(broken)
                    check(System.getProperty("fixture.cover.requests./primary.png") == "1")
                    check(System.getProperty("fixture.cover.requests./fallback.png") == "1")
                    check(System.getProperty("fixture.cover.requests./broken.png") == "2")
                } else {
                    cover(broken)
                    check(System.getProperty("fixture.cover.requests./primary.png") == null)
                }
                snapshot("browse")
                onNodeWithTag("content:${first.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settled()
                cover(first)
                if (mode == "cover-write") {
                    onNodeWithTag("preview-favourite").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.isFavourite }
                    settled()
                    onNodeWithTag("preview-read").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.image != null }
                    settled()
                    onNodeWithTag("reader-back").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    settled()
                }
                onNodeWithText("收藏", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.LIBRARY }
                settled()
                cover(first)
                onNodeWithText("历史", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.HISTORY }
                settled()
                cover(first)
                if (mode == "cover-write") check(System.getProperty("fixture.cover.requests./primary.png") == "1")
                snapshot("history")
            }
            if (mode == "cover-write") runBlocking {
                val content = requireNotNull(primary)
                val large = session.sources.fetchCover(
                    content.copy(largeCoverUrl = "https://fixture.invalid/large.png"), true)
                check(large.contentId == content.id && large.contentType == "image/png")
                check(System.getProperty("fixture.cover.requests./large.png") == "1")
                val slow = content.copy(coverUrl = "https://fixture.invalid/slow.png")
                val first = async(Dispatchers.IO) { session.sources.fetchCover(slow) }
                withTimeout(5000) { while (System.getProperty("fixture.cover.slow.entered") == null) delay(20) }
                first.cancelAndJoin()
                val next = async(Dispatchers.IO) { session.sources.describe(content.source.name) }
                check(withTimeoutOrNull(250) { next.await() } == null) { "Source gate released before its callback" }
                System.setProperty("fixture.cover.slow.release", "yes")
                check(withTimeout(5000) { next.await() }.isCoverFetchingSupported)
                check(System.getProperty("fixture.cover.closed./slow.png") == "yes")
                Files.list(session.storage.paths.images).use { files ->
                    check(files.noneMatch { it.fileName.toString().endsWith(".part") })
                }
            }
        } finally {
            System.setProperty("fixture.cover.slow.release", "yes")
            runBlocking { controller.shutdown() }
        }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
