package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.*
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import java.nio.file.Files
import java.nio.file.Path

/** Actual UI confirmation and independent-process persistence using only authored library data. */
internal object DesktopBackupsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val file = root.resolveSibling("library-backup.zip")
        val exported = root.resolveSibling("exported-backup.zip")
        if (mode.endsWith("write")) runBlocking {
            DesktopRuntime.open(root.resolveSibling("backup-original")).use { runtime ->
                val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
                val chapter = SourceChapter(Long.MIN_VALUE, "备份章节", 1f, 0, "/chapter", null, 0, null, source)
                val content = SourceContent(Long.MAX_VALUE, "Windows 中文备份作品", emptySet(), "/work",
                    "https://fixture.invalid/work", -1f, null, null, emptySet(), null, emptySet(), source, chapters = listOf(chapter))
                val library = DesktopLibrary(runtime.database) { source }
                library.addFavourite(content)
                library.recordPage(content, chapter, 2, 10, scroll = 64f)
                runtime.database.getBookmarksDao().upsert(listOf(BookmarkEntity(Long.MAX_VALUE, Long.MIN_VALUE,
                    chapter.id, 2, 64, "https://fixture.invalid/page", 20, .3f)))
                runtime.database.getStatsDao().upsert(StatsEntity(Long.MAX_VALUE, 20, 1200, 3))
                DesktopLibraryBackup(runtime.database).export(file)
            }
        }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        var unconfirmed: DesktopBackupArchive? = null
        try {
            runDesktopComposeUiTest(width = if (mode.endsWith("read")) 920 else 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(name: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(Path.of(args[3]).resolve("$mode-$name.png"), it.bytes) }
                    }
                }
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:备份与恢复").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.BACKUPS }
                idle()
                onNodeWithTag("backup-export").assertExists()
                onNodeWithTag("backup-open").assertExists()
                // Android's local copy rows: on by default, with the trim switch and its count under it.
                onNodeWithTag("webdav-keep-local").performScrollTo().assertExists()
                onNodeWithTag("webdav-trim-local").performScrollTo().assertExists()
                onNodeWithTag("webdav-local-count").performScrollTo().assertExists()
                onNodeWithTag("webdav-local-status").performScrollTo().assertExists()
                // Android keeps "auto restore on startup" in the same section, off and dormant until a server is saved.
                onNodeWithTag("webdav-auto-restore").performScrollTo().assertExists()
                onNodeWithTag("webdav-auto-restore").assertIsNotEnabled()
                check(!controller.webDavUi.value.settings.autoRestore)
                check(controller.webDavUi.value.settings.keepLocalCopy)
                // The read phase reopens a root whose write phase already left a copy behind.
                check(controller.webDavUi.value.localFiles.all {
                    Regex("""kototoro_\d{8}-\d{4}\.bk\.zip""").matches(it.fileName.toString())
                })
                snapshot("local-copy")
                if (mode.endsWith("write")) {
                    // Turning the keep switch off hides the rest of the local section, exactly like Android's page.
                    onNodeWithTag("webdav-keep-local").performScrollTo().performClick()
                    idle()
                    check(!controller.webDavUi.value.settings.keepLocalCopy)
                    onNodeWithTag("webdav-trim-local").assertDoesNotExist()
                    onNodeWithTag("webdav-keep-local").performClick()
                    idle()
                    check(controller.webDavUi.value.settings.keepLocalCopy)
                    onNodeWithTag("webdav-trim-local").assertExists()
                    // A local-only destination is Android's periodical backup without WebDAV: the timer writes the copy.
                    controller.webDavSettings(DesktopWebDavSettings(intervalHours = 1, keepLocalCopy = true))
                    waitUntil(timeoutMillis = 30_000) { controller.webDavUi.value.localFiles.isNotEmpty() }
                    val local = controller.webDavUi.value.localFiles.first()
                    check(Regex("""kototoro_\d{8}-\d{4}\.bk\.zip""").matches(local.fileName.toString()))
                    check(Files.size(local) > 0L)
                    check(local.parent == session.storage.paths.backups)
                    // The settings write re-lays the page out; scroll back to the filled rows before the snapshot.
                    onNodeWithTag("webdav-local-status").performScrollTo()
                    waitForIdle()
                    snapshot("local-copy-filled")
                    // A saved destination is what enables the auto-restore switch; it starts off, like Android.
                    controller.webDavSettings(DesktopWebDavSettings(serverUrl = "https://fixture.invalid/dav"))
                    onNodeWithTag("webdav-auto-restore").performScrollTo()
                    waitForIdle()
                    onNodeWithTag("webdav-auto-restore").assertIsEnabled()
                    onNodeWithTag("webdav-auto-restore").assertIsOff()
                    snapshot("auto-restore")
                    // Back to the factory settings so the rest of the probe runs without a timer behind it.
                    controller.webDavSettings(DesktopWebDavSettings())
                    idle()
                    check(controller.webDavUi.value.settings.intervalHours == 0)
                }
                check(controller.state.value.sources.isEmpty())
                if (mode.endsWith("write")) {
                    runBlocking { controller.previewLibraryBackup(file).join() }
                    idle()
                    onNodeWithTag("backup-preview").assertExists()
                    onNodeWithText("书签：1 条").assertExists()
                    onNodeWithText("阅读统计：1 条").assertExists()
                    check(runBlocking { session.library.favourites() }.isEmpty())
                    check(runBlocking { session.library.progress(Long.MAX_VALUE) } == null)
                    check(runBlocking { session.storage.database.getBookmarksDao().find(Long.MAX_VALUE, Long.MIN_VALUE) } == null)
                    check(runBlocking { session.storage.database.getStatsDao().find(Long.MAX_VALUE, 20) } == null)
                    snapshot("preview")
                    onNodeWithTag("backup-cancel").performScrollTo().performClick()
                    idle()
                    check(controller.backupPreview.value == null)
                    check(runBlocking { session.library.favourites() }.isEmpty())
                    check(runBlocking { session.storage.database.getBookmarksDao().find(Long.MAX_VALUE, Long.MIN_VALUE) } == null)
                    runBlocking { controller.previewLibraryBackup(file).join() }
                    idle()
                    onNodeWithTag("backup-confirm").performScrollTo().performClick()
                    idle()
                    check(controller.backupPreview.value == null)
                    check(controller.state.value.message?.startsWith("合并恢复完成") == true)
                    runBlocking { controller.exportLibraryBackup(exported).join() }
                    idle()
                } else {
                    runBlocking { controller.previewLibraryBackup(exported).join() }
                    idle()
                    onNodeWithTag("backup-preview").assertExists()
                    onNodeWithTag("backup-cancel").performScrollTo().performClick()
                    idle()
                }
                for (navigation in listOf("收藏", "历史")) {
                    onNodeWithTag("nav:$navigation").performClick()
                    idle()
                    onNodeWithTag("content:${Long.MAX_VALUE}").assertExists()
                    onNodeWithText("Windows 中文备份作品").assertExists()
                    snapshot(navigation)
                }
                val progress = runBlocking { session.library.progress(Long.MAX_VALUE) }
                check(progress?.chapterId == Long.MIN_VALUE && progress.page == 2 && progress.scroll == 64f)
                val bookmark = runBlocking { session.storage.database.getBookmarksDao().find(Long.MAX_VALUE, Long.MIN_VALUE) }
                check(bookmark?.chapterId == Long.MIN_VALUE && bookmark.page == 2 && bookmark.scroll == 64)
                check(runBlocking { session.storage.database.getStatsDao().find(Long.MAX_VALUE, 20) } ==
                    StatsEntity(Long.MAX_VALUE, 20, 1200, 3))
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:备份与恢复").performClick()
                idle()
                runBlocking { controller.previewLibraryBackup(exported).join() }
                idle()
                unconfirmed = controller.backupPreview.value
                check(unconfirmed != null)
            }
        } finally { runBlocking { controller.shutdown() } }
        runBlocking { DesktopRuntime.open(root).use { runtime ->
            check(DesktopLibrary(runtime.database) { null }.favourites().single().id == Long.MAX_VALUE)
            try { DesktopLibraryBackup(runtime.database).restore(requireNotNull(unconfirmed)); error("Preview leaked after shutdown") }
            catch (expected: IllegalStateException) { check(expected.message?.contains("预览已经关闭") == true) }
        } }
        val moved = root.resolveSibling("closed-$mode")
        Files.move(root, moved); Files.move(moved, root)
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
