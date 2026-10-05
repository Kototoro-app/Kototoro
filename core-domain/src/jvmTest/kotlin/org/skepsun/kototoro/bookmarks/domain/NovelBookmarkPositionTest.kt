package org.skepsun.kototoro.bookmarks.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64

class NovelBookmarkPositionTest {
    @Test
    fun `all supported legacy previews share plain text whitespace and truncation rules`() {
        val html = "<p>中文 <b>正文</b></p><script>ignore()</script><style>hide</style><p>第二段</p>"
        val data = "DATA:TEXT/HTML;charset=utf-8;BASE64," + Base64.Default.encode(html.encodeToByteArray())
        assertEquals("中文 正文 第二段", parseNovelBookmarkPreview(html))
        assertEquals(parseNovelBookmarkPreview(html), parseNovelBookmarkPreview(data))
        assertEquals("中文 正文", parseNovelBookmarkPreview("中文\n\t  正文"))
        assertEquals("啊".repeat(200), parseNovelBookmarkPreview("啊".repeat(300)))
    }

    @Test
    fun `media URLs unsupported or malformed data never become excerpts`() {
        for (value in listOf(null, "", " \t ", "HTTPS://example.invalid/cover", "file:/cover", "content://media/1",
            "data:image/png;base64,aGVsbG8=", "data:text/html;base64,%%%%", "data:text/html,<p>text</p>")) {
            assertEquals("", parseNovelBookmarkPreview(value), value)
        }
    }

    @Test
    fun `base64 whitespace and text plain work without Android APIs`() {
        val value = Base64.Default.encode("文字\n正文".encodeToByteArray()).chunked(4).joinToString("\r\n")
        assertEquals("文字 正文", parseNovelBookmarkPreview("data:text/plain;base64,$value"))
    }

    @Test
    fun `one excerpt locates different block and page indices even across page boundaries`() {
        val blocks = listOf("章名", "", "第一段正文。", "目标段落内容，继续阅读。", "尾段。")
        val pages = listOf("章名 第一段正", "文。目标段落", "内容，继续阅读。 尾段。")
        assertEquals(3, resolveNovelBookmarkPosition(blocks, "目标段落内容，继续阅读。", 700, .7f))
        assertEquals(1, resolveNovelBookmarkPosition(pages, "目标段落内容，继续阅读。", 3, .7f))
        assertEquals(3, resolveNovelBookmarkPosition(blocks, "落内容，继续阅读。", 1, .7f))
    }

    @Test
    fun `whitespace indentation and nonbreaking spaces do not change text anchors`() {
        val blocks = listOf("第一段", "　　目标\n段落\u00a0内容\t末尾")
        assertEquals(1, resolveNovelBookmarkPosition(blocks, "目标 段落 内容 末尾", 0, .5f))
    }

    @Test
    fun `empty image slots preserve boundaries and legacy positions`() {
        val blocks = listOf("第一段", "", "目标段落", "")
        assertEquals(2, resolveNovelBookmarkPosition(blocks, "目标段落", 0, 0f))
        assertEquals(1, resolveNovelBookmarkPosition(blocks, "", 1, 0f))
        assertNull(resolveNovelBookmarkPosition(blocks, "", 4, 0f))
        assertNull(resolveNovelBookmarkPosition(emptyList(), "", 0, 0f))
    }

    @Test
    fun `missing text does not fall back to a plausible but wrong numeric page`() {
        assertNull(resolveNovelBookmarkPosition(listOf("第一段", "第二段"), "原来的正文", 1, .5f))
    }

    @Test
    fun `repeated excerpts use relative progress and invalid progress remains deterministic`() {
        val blocks = listOf("重复文字", "中间文字", "重复文字")
        assertEquals(2, resolveNovelBookmarkPosition(blocks, "重复文字", 0, 1f))
        assertEquals(0, resolveNovelBookmarkPosition(blocks, "重复文字", 2, Float.NaN))
        assertEquals(0, resolveNovelBookmarkPosition(listOf("啊".repeat(100_000), "最后"), "啊", 0, 0f))
    }

    @Test
    fun `branch progress and its chapter hint have the same meaning for every engine`() {
        val progress = novelBookmarkProgress(1, 3, 4, 10)
        assertEquals(.5f, progress)
        assertEquals(.5f, novelBookmarkChapterProgress(progress, 1, 3))
        assertEquals(0f, novelBookmarkChapterProgress(Float.NaN, 0, 2))
        assertThrows(IllegalArgumentException::class.java) { novelBookmarkProgress(3, 3, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { novelBookmarkProgress(0, 3, 1, 1) }
    }
}
