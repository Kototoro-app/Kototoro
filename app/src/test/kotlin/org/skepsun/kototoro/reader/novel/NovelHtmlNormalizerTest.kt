package org.skepsun.kototoro.reader.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T4A.1 NovelHtmlNormalizer 单元测试（纯 JVM，Jsoup 直接可用）。
 *
 * 断言基于 Jsoup 1.22.1 Safelist 的实测语义：
 * - 有 baseUrl 时，相对 src/href 解析为绝对 URL（http/https 协议方保留）；
 * - 无 baseUrl 时，相对 src 因协议校验失败被丢弃，data-src 作为普通属性原样保留；
 * - data: 内联图协议在 addProtocols 白名单内，原样保留。
 */
class NovelHtmlNormalizerTest {

    @Test
    fun `script block including content is removed`() {
        val html = "<p>正文</p><script>var x = 'pwn'; alert(1);</script><p>结尾</p>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("script", ignoreCase = true))
        assertFalse(out.contains("pwn"))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("正文"))
        assertTrue(out.contains("结尾"))
    }

    @Test
    fun `uppercase SCRIPT is removed too`() {
        val html = "<p>前</p><SCRIPT>evil()</SCRIPT><p>后</p>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("evil"))
        assertFalse(out.contains("SCRIPT"))
        assertTrue(out.contains("前") && out.contains("后"))
    }

    @Test
    fun `style block is removed`() {
        val html = "<p>首</p><style>.x { color: red }</style><p>尾</p>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("style", ignoreCase = true))
        assertFalse(out.contains("color"))
        assertTrue(out.contains("首") && out.contains("尾"))
    }

    @Test
    fun `iframe object embed form input are removed`() {
        val html = "<div>a</div><iframe src=\"https://evil.example.com\"></iframe>" +
            "<object data=\"https://evil.example.com/x\"></object><embed src=\"https://evil.example.com/y\">" +
            "<form action=\"https://evil.example.com\"><input value=\"v\"></form><div>z</div>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("iframe", ignoreCase = true))
        assertFalse(out.contains("object", ignoreCase = true))
        assertFalse(out.contains("embed", ignoreCase = true))
        assertFalse(out.contains("form", ignoreCase = true))
        assertFalse(out.contains("input", ignoreCase = true))
        assertTrue(out.contains("a") && out.contains("z"))
    }

    @Test
    fun `onclick onerror event attributes are stripped`() {
        val html = "<img src=\"https://cdn.example.com/a.jpg\" onclick=\"evil()\" onerror=\"x()\">" +
            "<p onclick=\"evil()\">文本</p>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("onclick", ignoreCase = true))
        assertFalse(out.contains("onerror", ignoreCase = true))
        assertFalse(out.contains("evil"))
        assertTrue(out.contains("https://cdn.example.com/a.jpg"))
        assertTrue(out.contains("文本"))
    }

    @Test
    fun `javascript href and javascript img src are dropped`() {
        val html = "<a href=\"javascript:alert(1)\">bad</a><img src=\"javascript:alert(1)\">" +
            "<a href=\"https://ok.example.com/x\">ok</a>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("javascript", ignoreCase = true))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("https://ok.example.com/x"))
        assertTrue(out.contains("bad"))
    }

    @Test
    fun `relative img src with baseUrl is resolved to absolute and preserved`() {
        val html = "<img src=\"/img/a.jpg\" alt=\"风景\">" +
            "<img src=\"../img/b.png\"><img src=\"//cdn.example.com/x.png\"><img src=\"webp/y.webp\">"
        val out = NovelHtmlNormalizer.sanitize(html, "https://example.com/novels/ch/1")
        assertTrue(out.contains("https://example.com/img/a.jpg"))
        assertTrue(out.contains("https://example.com/novels/img/b.png"))
        assertTrue(out.contains("https://cdn.example.com/x.png"))
        assertTrue(out.contains("https://example.com/novels/ch/webp/y.webp"))
        assertFalse(out.contains("\"/img/a.jpg\""))
    }

    @Test
    fun `relative href with baseUrl is resolved to absolute`() {
        val out = NovelHtmlNormalizer.sanitize("<a href=\"/rel/path\">链接</a>", "https://example.com/novels/")
        assertTrue(out.contains("https://example.com/rel/path"))
        assertTrue(out.contains("链接"))
    }

    @Test
    fun `data uri image is preserved for offline use`() {
        val html = "<img src=\"data:image/png;base64,QUJD\">"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertTrue(out.contains("data:image/png;base64,QUJD"))
    }

    @Test
    fun `text formatting tags p b i h ul li blockquote and http anchor are preserved`() {
        val html = "<h1>标题</h1><p>段落</p><b>粗</b><i>斜</i>" +
            "<ul><li>一</li><li>二</li></ul><blockquote>引用</blockquote>" +
            "<pre>code</pre><a href=\"https://ok.example.com/x\">链接</a>"
        val out = NovelHtmlNormalizer.sanitize(html)
        for (tag in listOf("h1", "p", "b", "i", "ul", "li", "blockquote", "pre", "a")) {
            assertTrue(out.contains("<$tag"), "expected <$tag> preserved in: $out")
            assertTrue(out.contains("</$tag>"), "expected </$tag> preserved in: $out")
        }
        assertTrue(out.contains("https://ok.example.com/x"))
    }

    @Test
    fun `plain text is preserved`() {
        val out = NovelHtmlNormalizer.sanitize("第一段。\n\n第二段。")
        assertEquals("第一段。\n\n第二段。", out)
    }

    @Test
    fun `nested malicious attributes are stripped from kept tags`() {
        val html = "<a href=\"javascript:alert(1)\" onclick=\"x()\" title=\"t\">bad</a><div onclick=\"x\">d</div>"
        val out = NovelHtmlNormalizer.sanitize(html)
        assertFalse(out.contains("href="))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("title="))
        assertTrue(out.contains("bad") && out.contains("d"))
        assertEquals("<a>bad</a>d", out)
    }

    @Test
    fun `empty and blank inputs produce empty output`() {
        assertEquals("", NovelHtmlNormalizer.sanitize(""))
        assertEquals("", NovelHtmlNormalizer.sanitize("   \n\t  "))
    }

    @Test
    fun `without baseUrl relative img src is dropped but data-src is retained`() {
        // Jsoup 协议校验语义：无 baseUri 时相对 src 无法判协议 → 丢弃；
        // data-src 是普通属性（不参与协议校验）→ 原样保留。
        val out = NovelHtmlNormalizer.sanitize("<img src=\"images/pic1.jpg\" data-src=\"images/pic1.jpg\">")
        assertEquals("<img data-src=\"images/pic1.jpg\">", out)
        assertFalse(out.contains("<img src=\""))
        assertTrue(out.contains("data-src=\"images/pic1.jpg\""))
    }

    @Test
    fun `output is body content without html head wrapper`() {
        val out = NovelHtmlNormalizer.sanitize("<html><head><title>t</title></head><body><p>正文</p></body></html>")
        assertFalse(out.contains("<html"))
        assertFalse(out.contains("<head"))
        assertFalse(out.contains("<title>"))
        assertTrue(out.contains("<p>正文</p>"))
    }
}
