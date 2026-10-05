package org.skepsun.kototoro.bookmarks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview

class NovelBookmarkPreviewHelperTest {

    @Test
    fun `extractNovelBookmarkPreview returns empty for null or blank input`() {
        assertEquals("", extractNovelBookmarkPreview(null))
        assertEquals("", extractNovelBookmarkPreview(""))
        assertEquals("", extractNovelBookmarkPreview("   \n\t  "))
    }

    @Test
    fun `extractNovelBookmarkPreview returns empty for image or file URLs`() {
        assertEquals("", extractNovelBookmarkPreview("http://example.com/cover.jpg"))
        assertEquals("", extractNovelBookmarkPreview("https://example.com/image.png"))
        assertEquals("", extractNovelBookmarkPreview("file:///data/user/0/cache/snap.jpg"))
        assertEquals("", extractNovelBookmarkPreview("content://media/external/images/1"))
    }

    @Test
    fun `extractNovelBookmarkPreview returns clean plain text for plain text input`() {
        val text = "这是小说的第一页内容，描述了主人公在清晨醒来时的心理描写。"
        assertEquals(text, extractNovelBookmarkPreview(text))
    }

    @Test
    fun `extractNovelBookmarkPreview normalizes redundant whitespace in plain text`() {
        val raw = "这是小说的第一页内容，\n\n\n\t\t描述了主人公在清晨醒来时的心理描写。"
        val expected = "这是小说的第一页内容， 描述了主人公在清晨醒来时的心理描写。"
        assertEquals(expected, extractNovelBookmarkPreview(raw))
    }

    @Test
    fun `extractNovelBookmarkPreview strips HTML tags and script elements`() {
        val html = """
            <html>
            <head><title>第一章</title><style>.bold{font-weight:bold;}</style></head>
            <body>
            <p>第一章 陨落的天才</p>
            <p>“七段斗之气！”望着测验魔石碑上面闪亮得甚至有些刺眼的五个大字。</p>
            <script>console.log("ignore");</script>
            </body>
            </html>
        """.trimIndent()

        val preview = extractNovelBookmarkPreview(html)
        assertEquals("第一章 陨落的天才 “七段斗之气！”望着测验魔石碑上面闪亮得甚至有些刺眼的五个大字。", preview)
    }

    @Test
    fun `extractNovelBookmarkPreview decodes base64 data html URLs`() {
        val rawHtml = "<p>在阳光明媚的早晨，故事拉开了帷幕。</p>"
        val base64 = java.util.Base64.getEncoder().encodeToString(rawHtml.toByteArray(Charsets.UTF_8))
        val dataUrl = "data:text/html;base64,$base64"

        val preview = extractNovelBookmarkPreview(dataUrl)
        assertEquals("在阳光明媚的早晨，故事拉开了帷幕。", preview)
    }

    @Test
    fun `extractNovelBookmarkPreview truncates long text up to 200 characters`() {
        val longText = "啊".repeat(300)
        val preview = extractNovelBookmarkPreview(longText)
        assertEquals(200, preview.length)
        assertEquals("啊".repeat(200), preview)
    }
}
