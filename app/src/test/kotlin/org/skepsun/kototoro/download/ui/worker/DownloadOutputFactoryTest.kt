package org.skepsun.kototoro.download.ui.worker

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.exceptions.StorageWriteException
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File

/**
 * 「首选下载目录其实写不进去」是 issue #511 的实际场景：SAF 授权被回收后
 * `isWriteable()` 仍然报 true，只有真的创建一次才暴露。这里验证回退与最终报错。
 */
class DownloadOutputFactoryTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `falls back to the next candidate when the preferred root cannot create anything`() {
        val brokenDir = unusableDir()
        val brokenRoot = rootWith(brokenDir)
        // 第二个根目录「真的」能建目录：createDirectory 返回一个可用的空目录（没有 index.json）
        val createdDir = mockk<UniFile>(relaxed = true) {
            every { findFile(any()) } returns null
        }
        val goodDir = mockk<UniFile>(relaxed = true) {
            every { findFile(any()) } returns null
            every { createDirectory(any()) } returns createdDir
        }
        val goodRoot = rootWith(goodDir)

        val output = runBlocking {
            createOutputWithFallback(
                candidates = listOf(brokenRoot, goodRoot),
                manga = manga(),
                format = DownloadFormat.MULTIPLE_CBZ,
                cacheDir = cacheDir,
            )
        }

        assertSame(createdDir, output.rootFile)
        verify(atLeast = 1) { brokenDir.createDirectory(any()) }
        verify(atLeast = 1) { goodDir.createDirectory(any()) }
    }

    @Test
    fun `reports which target failed when no candidate is usable`() {
        val first = unusableDir()
        val second = unusableDir()

        val error = assertThrows(StorageWriteException::class.java) {
            runBlocking {
                createOutputWithFallback(
                    candidates = listOf(rootWith(first), rootWith(second)),
                    manga = manga(),
                    format = DownloadFormat.MULTIPLE_CBZ,
                    cacheDir = cacheDir,
                )
            }
        }
        verify(atLeast = 1) { first.createDirectory(any()) }
        verify(atLeast = 1) { second.createDirectory(any()) }
        assertTrue(error.message!!.contains("Test_manga"), error.message.orEmpty())
        assertTrue(!error.message!!.contains("Required value was null"), error.message.orEmpty())
    }

    private fun unusableDir(): UniFile = mockk<UniFile>(relaxed = true) {
        every { findFile(any()) } returns null
        every { createDirectory(any()) } returns null
        every { createFile(any()) } returns null
    }

    private fun rootWith(dir: UniFile): LocalStorageRoot = mockk<LocalStorageRoot>(relaxed = true) {
        every { file } returns dir
    }

    private fun manga(): Content = Content(
        id = 42L,
        title = "Test manga",
        altTitles = emptySet(),
        url = "/manga/42",
        publicUrl = "https://example.com/manga/42",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        chapters = null,
        source = TestSource,
    )

    private data object TestSource : ContentSource {
        override val name = "TEST"
        override val locale = "en"
        override val contentType = ContentType.MANGA
    }
}
