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
