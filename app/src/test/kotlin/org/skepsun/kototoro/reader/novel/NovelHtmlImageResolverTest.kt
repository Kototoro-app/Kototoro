package org.skepsun.kototoro.reader.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T4A.2 NovelHtmlImageResolver 单元测试（纯 JVM）。
 *
 * resolveImageSrc 语义基于 java.net.URI.resolve（已实测）：
 * - `//host/path` → `baseUrl 的 scheme + src`
 * - `/path` → `baseUrl 的 origin + path`
 * - `../x`、`x` → 按 baseUrl 目录语义解析
 * - data:/file:/http(s): 原样；解析失败返回原值
 */
class NovelHtmlImageResolverTest {

    // ---------- resolveImageSrc ----------

    @Test
    fun `protocol relative src is prefixed with baseUrl scheme`() {
        assertEquals(
            "https://cdn.example.com/x.png",
            NovelHtmlImageResolver.resolveImageSrc(
                "//cdn.example.com/x.png",
                "https://example.com/novels/ch/1",
            ),
        )
        assertEquals(
            "http://cdn.example.com/x.png",
            NovelHtmlImageResolver.resolveImageSrc(
                "//cdn.example.com/x.png",
                "http://example.com/a",
            ),
        )
    }

    @Test
    fun `root relative path resolves against baseUrl origin`() {
        assertEquals(
            "https://example.com/img/a.jpg",
            NovelHtmlImageResolver.resolveImageSrc("/img/a.jpg", "https://example.com/novels/ch/1"),
        )
    }

    @Test
    fun `parent relative path resolves against baseUrl directory`() {
        assertEquals(
            "https://example.com/novels/img/b.png",
            NovelHtmlImageResolver.resolveImageSrc("../img/b.png", "https://example.com/novels/ch/1"),
        )
    }

    @Test
    fun `plain relative name resolves against baseUrl directory`() {
        assertEquals(
            "https://example.com/novels/ch/webp/x.webp",
            NovelHtmlImageResolver.resolveImageSrc("webp/x.webp", "https://example.com/novels/ch/1"),
        )
        assertEquals(
            "https://example.com/novels/ch/images/pic1.jpg",
            NovelHtmlImageResolver.resolveImageSrc("images/pic1.jpg", "https://example.com/novels/ch/1"),
        )
    }

    @Test
    fun `absolute http and https srcs are returned unchanged`() {
        val src = "https://cdn2.example.com/y.webp"
        assertEquals(src, NovelHtmlImageResolver.resolveImageSrc(src, "https://example.com/a"))
        assertEquals("http://cdn.example.com/z.jpg", NovelHtmlImageResolver.resolveImageSrc("http://cdn.example.com/z.jpg", "https://example.com/a"))
        assertEquals("https://example.com:8443/img.png", NovelHtmlImageResolver.resolveImageSrc("https://example.com:8443/img.png", "https://example.com/a"))
    }

    @Test
    fun `data uri is returned unchanged`() {
        val src = "data:image/png;base64,QUJD"
        assertEquals(src, NovelHtmlImageResolver.resolveImageSrc(src, "https://example.com/a"))
    }

    @Test
    fun `file uri is returned unchanged`() {
        val src = "file:///storage/emulated/0/x.png"
        assertEquals(src, NovelHtmlImageResolver.resolveImageSrc(src, "https://example.com/a"))
    }

    @Test
    fun `null or blank baseUrl returns src unchanged`() {
        assertEquals("images/pic1.jpg", NovelHtmlImageResolver.resolveImageSrc("images/pic1.jpg", null))
        assertEquals("images/pic1.jpg", NovelHtmlImageResolver.resolveImageSrc("images/pic1.jpg", "  "))
    }

    @Test
    fun `invalid baseUrl returns src unchanged`() {
        assertEquals("images/pic1.jpg", NovelHtmlImageResolver.resolveImageSrc("images/pic1.jpg", "not a url"))
    }

    @Test
    fun `javascript src without baseUrl is returned unchanged`() {
        val src = "javascript:alert(1)"
        assertEquals(src, NovelHtmlImageResolver.resolveImageSrc(src, null))
    }

    // ---------- extractImageUrls ----------

    @Test
    fun `extractImageUrls prefers data-src and dedups preserving order`() {
        val html = "<img src=\"https://a.com/1.jpg\" data-src=\"https://a.com/1.webp\">" +
            "<img src=\"https://a.com/2.jpg\"><img data-src=\"https://a.com/1.webp\"><img src=\"https://a.com/3.jpg\">"
        assertEquals(
            listOf("https://a.com/1.webp", "https://a.com/2.jpg", "https://a.com/3.jpg"),
            NovelHtmlImageResolver.extractImageUrls(html),
        )
    }

    @Test
    fun `extractImageUrls resolves relative srcs with baseUrl`() {
        val html = "<img data-src=\"/img/a.jpg\"><img src=\"../b.png\"><img src=\"webp/c.webp\">"
        assertEquals(
            listOf(
                "https://example.com/img/a.jpg",
                "https://example.com/novels/b.png",
                "https://example.com/novels/ch/webp/c.webp",
            ),
            NovelHtmlImageResolver.extractImageUrls(html, "https://example.com/novels/ch/1"),
        )
    }

    @Test
    fun `extractImageUrls keeps data uris`() {
        assertEquals(
            listOf("data:image/png;base64,QUJD"),
            NovelHtmlImageResolver.extractImageUrls("<img src=\"data:image/png;base64,QUJD\">"),
        )
    }

    @Test
    fun `extractImageUrls drops javascript and vbscript srcs`() {
        val html = "<img src=\"javascript:alert(1)\"><img src=\"vbscript:msgbox(1)\"><img src=\"https://ok.com/i.png\">"
        assertEquals(listOf("https://ok.com/i.png"), NovelHtmlImageResolver.extractImageUrls(html))
    }

    @Test
    fun `extractImageUrls ignores imgs without src and blanks`() {
        assertEquals(emptyList<String>(), NovelHtmlImageResolver.extractImageUrls("前<img>后<img src=\"   \">终"))
        assertEquals(emptyList<String>(), NovelHtmlImageResolver.extractImageUrls(""))
        assertEquals(emptyList<String>(), NovelHtmlImageResolver.extractImageUrls("   "))
    }

    // ---------- applyRetryResults（T4A.5 纯函数） ----------

    private val baseHead = "00000000_00010001"
    private val baseTail = "00000000_00010002"
    private val basePage3 = "00000000_00010003"

    @Test
    fun `applyRetryResults keeps retried-success names and replaces retry-failed with placeholder`() {
        val html = """<p>x</p><img src="$baseHead.jpg" data-src="https://cdn.example.com/1.webp">""" +
            """<img src="$baseTail.jpg" data-src="https://cdn.example.com/2.jpg">"""
        val nameMap = mapOf(
            "https://cdn.example.com/1.webp" to "$baseHead.jpg",
            "https://cdn.example.com/2.jpg" to "$baseTail.jpg",
        )
        // img1 重试成功（沿用原计划名），img2 重试仍失败 → 占位名
        val retried = mapOf(
            "https://cdn.example.com/1.webp" to "$baseHead.jpg",
            "https://cdn.example.com/2.jpg" to "failed_1.jpg",
        )
        val result = applyRetryResults(html, nameMap, retried)
        assertEquals(
            mapOf(
                "https://cdn.example.com/1.webp" to "$baseHead.jpg",
                "https://cdn.example.com/2.jpg" to "failed_1.jpg",
            ),
            result.mapping,
        )
        assertTrue(result.html.contains("""src="$baseHead.jpg""""), "failed html: ${result.html}")
        assertTrue(result.html.contains("""src="failed_1.jpg""""), "failed html: ${result.html}")
        assertFalseText(result.html, "src=\"$baseTail.jpg\"")
    }

    @Test
    fun `applyRetryResults leaves untouched images and non-retried names intact`() {
        val html = """<img src="$baseHead.jpg"><img src="$baseTail.jpg"><img src="$basePage3.jpg">"""
        val nameMap = mapOf(
            "https://a.com/1.webp" to "$baseHead.jpg",
            "https://a.com/2.jpg" to "$baseTail.jpg",
            "https://a.com/3.png" to "$basePage3.jpg",
        )
        val retried = mapOf("https://a.com/2.jpg" to "failed_1.jpg")
        val result = applyRetryResults(html, nameMap, retried)
        val changed = result.mapping.filterKeys { url -> result.mapping[url] != nameMap[url] }
        assertEquals(mapOf("https://a.com/2.jpg" to "failed_1.jpg"), changed)
        assertTrue(result.mapping["https://a.com/1.webp"] == "$baseHead.jpg")
        assertTrue(result.mapping["https://a.com/3.png"] == "$basePage3.jpg")
        assertTrue(result.html.contains("""src="$baseHead.jpg""""))
        assertTrue(result.html.contains("""src="failed_1.jpg""""))
        assertTrue(result.html.contains("""src="$basePage3.jpg""""))
    }

    @Test
    fun `applyRetryResults with empty retried is identity on mapping`() {
        val html = """<img src="$baseHead.jpg"><img src="$baseTail.jpg">"""
        val nameMap = mapOf(
            "https://a.com/1.webp" to "$baseHead.jpg",
            "https://a.com/2.jpg" to "$baseTail.jpg",
        )
        val result = applyRetryResults(html, nameMap, emptyMap())
        assertEquals(nameMap, result.mapping)
        assertTrue(result.html.contains("""src="$baseHead.jpg""""))
        assertTrue(result.html.contains("""src="$baseTail.jpg""""))
    }

    @Test
    fun `applyRetryResults full closure all retries succeed keeps planned names`() {
        // 对应 T4A.6：「部分失败 → 全部重试成功 → HTML 与 mapping 保持原计划名」闭环
        val html = """<img src="$baseHead.jpg"><img src="$baseTail.jpg">"""
        val nameMap = mapOf(
            "https://a.com/1.webp" to "$baseHead.jpg",
            "https://a.com/2.jpg" to "$baseTail.jpg",
        )
        val retried = mapOf(
            "https://a.com/1.webp" to "$baseHead.jpg",
            "https://a.com/2.jpg" to "$baseTail.jpg",
        )
        val result = applyRetryResults(html, nameMap, retried)
        assertEquals(nameMap, result.mapping)
        assertTrue(result.html.contains("""src="$baseHead.jpg""""), "failed html: ${result.html}")
        assertTrue(result.html.contains("""src="$baseTail.jpg""""), "failed html: ${result.html}")
        assertFalseText(result.html, "failed_")
    }

    private fun assertFalseText(actual: String, fragment: String) {
        assertTrue(!actual.contains(fragment), "unexpected fragment: $fragment in $actual")
    }
}
