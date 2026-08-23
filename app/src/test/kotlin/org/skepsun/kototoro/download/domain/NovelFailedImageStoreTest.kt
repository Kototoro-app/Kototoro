package org.skepsun.kototoro.download.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * T4A.5 NovelFailedImageStore 单元测试（纯 JVM：kotlinx.serialization + File）。
 */
class NovelFailedImageStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `write then read round trips all fields`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 42L, 7L)
        val failed = listOf(
            FailedChapterImage(
                chapterId = 7L,
                url = "https://cdn.example.com/1.jpg",
                localName = null,
                error = "HTTP 404",
                failedAt = 1_700_000_000_000L,
            ),
            FailedChapterImage(
                chapterId = 7L,
                url = "https://cdn.example.com/2.png",
                localName = "failed_1.jpg",
                error = null,
                failedAt = 1_700_000_000_001L,
            ),
        )
        NovelFailedImageStore.write(file, 7L, failed)
        assertEquals(failed, NovelFailedImageStore.read(file, 7L))
        assertTrue(file.exists())
        assertTrue(file.name.startsWith("novel_failed_images_42_7.json"))
    }

    @Test
    fun `empty list round trips`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 1L, 2L)
        NovelFailedImageStore.write(file, 2L, emptyList())
        assertEquals(emptyList<FailedChapterImage>(), NovelFailedImageStore.read(file, 2L))
    }

    @Test
    fun `urls with escape characters and unicode round trip`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 9L, 3L)
        val weird = "https://cdn.example.com/x\"y\\z 中文 🖼️?a=1&b=\"2\""
        val failed = listOf(
            FailedChapterImage(
                chapterId = 3L,
                url = weird,
                localName = "p (1).jpg",
                error = "timeout \" 5s \" \\n",
                failedAt = 123L,
            ),
        )
        NovelFailedImageStore.write(file, 3L, failed)
        assertEquals(failed, NovelFailedImageStore.read(file, 3L))
    }

    @Test
    fun `missing file returns empty list`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 5L, 99L)
        assertEquals(emptyList<FailedChapterImage>(), NovelFailedImageStore.read(file, 99L))
    }

    @Test
    fun `corrupted json returns empty list silently`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 5L, 100L)
        file.writeText("{ not valid json !!!")
        assertEquals(emptyList<FailedChapterImage>(), NovelFailedImageStore.read(file, 100L))
    }

    @Test
    fun `read filters entries by chapterId`() {
        val file = NovelFailedImageStore.sidecarFile(tempDir, 5L, 7L)
        // 手写一个混入其它章节的 JSON（防御性过滤）
        val mixed = listOf(
            FailedChapterImage(chapterId = 7L, url = "https://a/1.jpg", failedAt = 1L),
            FailedChapterImage(chapterId = 999L, url = "https://a/other.jpg", failedAt = 2L),
        )
        NovelFailedImageStore.write(file, 7L, mixed)
        val read = NovelFailedImageStore.read(file, 7L)
        assertEquals(listOf(FailedChapterImage(chapterId = 7L, url = "https://a/1.jpg", failedAt = 1L)), read)
    }
}
