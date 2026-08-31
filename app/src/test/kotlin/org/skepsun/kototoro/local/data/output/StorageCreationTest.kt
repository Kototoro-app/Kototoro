package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.StorageWriteException
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File

/**
 * UniFile 创建失败的方式是**返回 null**，不是抛异常。以前调用点用无 message 的
 * `checkNotNull`，用户只能看到 "Required value was null."（issue #511）；
 * 现在必须是带根目录与目标名的 [StorageWriteException]。
 */
class StorageCreationTest {

    @Test
    fun `createFileOrThrow reports the failed target instead of a bare null check`() {
        val dir = failingUniFile(canCreateFile = false)
        val error = assertThrows(StorageWriteException::class.java) {
            dir.createFileOrThrow("chapter.cbz")
        }
        assertTrue(error.message!!.contains("chapter.cbz"), error.message.orEmpty())
        assertTrue(error.message!!.isNotBlank())
        assertTrue(!error.message!!.contains("Required value was null"), error.message.orEmpty())
    }

    @Test
    fun `createDirectoryOrThrow reports the failed target instead of a bare null check`() {
        val dir = failingUniFile(canCreateDirectory = false)
        val error = assertThrows(StorageWriteException::class.java) {
            dir.createDirectoryOrThrow("Test_manga")
        }
        assertTrue(error.message!!.contains("Test_manga"), error.message.orEmpty())
        assertTrue(error.message!!.contains("directory"), error.message.orEmpty())
    }

    @Test
    fun `getOrCreate surfaces a storage error when nothing can be created`() {
        val root = mockk<LocalStorageRoot>(relaxed = true)
        every { root.file } returns failingUniFile(canCreateFile = false, canCreateDirectory = false)

        val error = assertThrows(StorageWriteException::class.java) {
            runBlocking {
                LocalContentOutput.getOrCreate(
                    root = root,
                    manga = manga(),
                    format = DownloadFormat.MULTIPLE_CBZ,
                    cacheDir = File("build/tmp/missing-cache-dir"),
                )
            }
        }
        assertTrue(!error.message!!.contains("Required value was null"), error.message.orEmpty())
        assertTrue(error.message!!.contains("Test_manga"), error.message.orEmpty())
    }

    private fun failingUniFile(canCreateFile: Boolean = true, canCreateDirectory: Boolean = true): UniFile =
        mockk<UniFile>(relaxed = true) {
            every { findFile(any()) } returns null
            if (!canCreateFile) {
                every { createFile(any()) } returns null
            }
            if (!canCreateDirectory) {
                every { createDirectory(any()) } returns null
            }
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
