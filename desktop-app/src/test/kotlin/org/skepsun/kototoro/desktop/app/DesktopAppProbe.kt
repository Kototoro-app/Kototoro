package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlin.system.exitProcess

/** Own real UI and SDK in a child JVM: AndroidCompat's process-global main Looper cannot be restarted. */
object DesktopAppProbe {
    @OptIn(ExperimentalTestApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args[0].startsWith("appearance-")) {
                DesktopAppearanceProbe.run(args)
                return
            }
            if (args[0].startsWith("bookmarks-")) {
                DesktopBookmarksProbe.run(args)
                return
            }
            if (args[0] == "upscale") {
                DesktopUpscaleProbe.run(args)
                return
            }
            if (args[0].startsWith("cloudstream-")) {
                DesktopCloudstreamProbe.run(args)
                return
            }
            if (args[0] == "video") {
                DesktopVideoProbe.run(args)
                return
            }
            if (args[0] == "real-anime") {
                DesktopRealAnimeProbe.run(args)
                return
            }
            if (args[0] == "real-novel") {
                DesktopRealNovelProbe.run(args)
                return
            }
            if (args[0].startsWith("parsers-")) {
                DesktopParserProbe.run(args)
                return
            }
            if (args[0].startsWith("novel-directory")) {
                DesktopNovelDirectoryProbe.run(args)
                return
            }
            if (args[0].startsWith("tablet")) {
                DesktopTabletProbe.run(args)
                return
            }
            if (args[0].startsWith("repositories-")) {
                DesktopRepositoriesProbe.run(args)
                return
            }
            if (args[0].startsWith("backups-")) {
                DesktopBackupsProbe.run(args)
                return
            }
            if (args[0].startsWith("downloads-")) {
                DesktopDownloadsProbe.run(args)
                return
            }
            if (args[0].startsWith("filters")) {
                DesktopFilterProbe.run(args)
                return
            }
            if (args[0].startsWith("cover-")) {
                DesktopCoverProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-chapters-")) {
                DesktopChapterProbe.run(args)
                return
            }
            if (args[0] == "reader-tiles") {
                DesktopTilesProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-bounded-")) {
                DesktopBoundedImageProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-scroll-")) {
                DesktopScrollProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-gestures-")) {
                DesktopReaderGestureProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-camera-")) {
                DesktopReaderCameraProbe.run(args)
                return
            }
            if (args[0].startsWith("reader-")) {
                DesktopReaderProbe.run(args)
                return
            }
            if (args[0] == "challenge") {
                DesktopChallengeProbe.run(args)
                return
            }
            if (args[0] == "browser") {
                DesktopBrowserProbe.run(args)
                return
            }
            if (args[0].startsWith("window")) {
                DesktopWindowProbe.run(args)
                return
            }
            val mode = args[0]
            val root = Path.of(args[1])
            val reports = Files.createDirectories(Path.of(args[3]))
            if (Files.isRegularFile(Path.of(args[4]))) System.setProperty("kototoro.compat.bridge.exe", args[4])
            val session = runBlocking { DesktopSession.open(root) }
            val controller = DesktopController(session)
            try {
                if (mode == "write") runBlocking { controller.importJar(Path.of(args[2])).join() }
                check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
                runDesktopComposeUiTest(width = 1260, height = 850) {
                    setContent { DesktopApp(controller) }
                    fun settled() {
                        waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                        waitForIdle()
                        check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                    }
                    fun snapshot(name: String) {
                        val bitmap = onRoot().captureToImage()
                        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                            image.encodeToData()?.use { Files.write(reports.resolve("$mode-$name.png"), it.bytes) }
                        }
                    }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                    settled()
                    check(controller.state.value.sources.single().displayName == if (mode == "write") "initial" else "saved")
                    onNodeWithTag("source:MIHON_9007199254740993").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }
                    settled()
                    snapshot("browse")
                    onNodeWithTag("source-query").performTextInput("离线")
                    onNodeWithText("搜索").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.query == "离线" }
                    settled()
                    check(controller.state.value.items.single().title == "search 离线")
                    snapshot("search")
                    onNodeWithText("热门").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.query.isEmpty() }
                    settled()
                    val content = controller.state.value.items.single()
                    onNodeWithTag("content:${content.id}").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    settled()
                    onNodeWithText("Chapter").assertExists()
                    if (mode == "write") {
                        onNodeWithTag("preview-favourite").performClick()
                        waitUntil(timeoutMillis = 15_000) { controller.state.value.isFavourite }
                        settled()
                    } else check(controller.state.value.isFavourite)
                    snapshot("details")
                    onNodeWithTag("preview-read").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.image != null }
                    settled()
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("reader-page").fetchSemanticsNodes().size == 1 }
                    onNodeWithTag("reader-page").assertExists()
                    snapshot("reader")
                    check(runBlocking { session.library.progress(content.id) }?.chapterId == controller.state.value.chapter?.id)
                    onNodeWithTag("reader-back").performClick()
                    settled()
                    onNodeWithText("收藏", useUnmergedTree = true).performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.LIBRARY }
                    settled()
                    onNodeWithTag("content:${content.id}").assertExists()
                    onNodeWithText("历史", useUnmergedTree = true).performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.HISTORY }
                    settled()
                    onNodeWithTag("content:${content.id}").assertExists()
                    onNodeWithTag("nav:浏览").performClick()
                    settled()
                    onNodeWithTag("source:MIHON_9007199254740993").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.EXPLORE }
                    settled()
                    onNodeWithText("源设置").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.PREFERENCES }
                    settled()
                    val preferenceNodes = requireNotNull(controller.state.value.preferences).nodes
                    // Kototoro's User-Agent row accompanies every HTTP source's own controls.
                    check(preferenceNodes.any { it.id == "host:user_agent" }) { "$preferenceNodes" }
                    val node = preferenceNodes.single { !it.id.startsWith("host:") }
                    if (mode == "write") {
                        onNodeWithTag("preference:${node.id}:rejected").performClick()
                        waitUntil(timeoutMillis = 15_000) { controller.state.value.message == "来源拒绝了这项设置" }
                        settled()
                        check(controller.state.value.message == "来源拒绝了这项设置")
                        onNodeWithTag("preference:${node.id}:saved").performClick()
                        waitUntil(timeoutMillis = 15_000) {
                            controller.state.value.preferences?.nodes?.single { !it.id.startsWith("host:") }?.value == SourcePreferenceValue.Text("saved")
                        }
                        settled()
                    }
                    check(controller.state.value.preferences?.nodes?.single { !it.id.startsWith("host:") }?.value == SourcePreferenceValue.Text("saved"))
                    onNodeWithText("域名设置").assertExists()
                    snapshot("preferences")
                    if (mode == "write" && session.browser != null) {
                        check(session.browser.evaluate("<html><title>中文 desktop</title></html>", "document.title") == "\"中文 desktop\"")
                    }
                }
            } finally { runBlocking { controller.shutdown() } }
            runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
            val moved = root.resolveSibling("closed-$mode")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (true) {
                try { Files.move(root, moved); break } catch (error: IOException) {
                    if (System.nanoTime() >= deadline) throw error
                    // Evergreen may finish releasing its child-process profile handles after bridge shutdown.
                    Thread.sleep(100)
                }
            }
            Files.move(moved, root)
            println("DESKTOP_UI_OK=$mode")
            println("NETWORK_REQUESTS=0")
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
    }
}
