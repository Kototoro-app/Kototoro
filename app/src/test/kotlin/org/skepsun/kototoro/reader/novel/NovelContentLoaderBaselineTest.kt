package org.skepsun.kototoro.reader.novel

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.epub.EpubContentCache
import org.skepsun.kototoro.local.epub.EpubStorageManager
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.NovelChapterContent

/**
 * T0.4 基线测试：characterization 测试，固定当前 NovelContentLoader 对小说正文 HTML 的处理行为。
 *
 * 现状（Phase 4A 将以 Jsoup 安全化重构，本文件为其提供行为基线）：
 * - 正文 HTML→纯文本转换位于私有方法 NovelContentLoader.htmlToPlainText()，纯正则实现。
 * - 最小可测公开入口是 loadChapterContent()/loadChapterContentFlow()：通过 mock 的
 *   ContentRepository.getChapterContent() 返回 NovelChapterContent(html=…)，走真实生产路径。
 * - 其余依赖（LocalStorageCache / EpubStorageManager / MangaDatabase / EpubContentCache / Context）
 *   以 relaxed MockK mock 注入；ReplaceRuleRepository 懒加载自 Context.getSharedPreferences，
 *   在测试中无内容替换规则（空列表），因此 applyReplaceRules 为恒等变换，不干扰断言。
 *
 * 注意：这些断言“如实”固定当前（可能不理想）的行为，而不是修正缺陷。
 */
class NovelContentLoaderBaselineTest {

    // ---------- 测试夹具 ----------

    private fun newSource(): ContentSource = object : ContentSource {
        override val name: String = "baseline-test"
        override val locale: String = "zh"
        override val contentType: ContentType = ContentType.NOVEL
    }

    private fun newChapter(): ContentChapter = ContentChapter(
        id = 1L,
        title = "baseline",
        number = 1f,
        volume = 0,
        url = "https://example.com/chapter/1", // 非 epub/zip/file 分支，直走网络内容路径
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = newSource(),
    )

    private fun newLoader(): NovelContentLoader {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return NovelContentLoader(
            cache = mockk<LocalStorageCache>(relaxed = true),
            epubStorageManager = mockk<EpubStorageManager>(relaxed = true),
            mangaDatabase = mockk<MangaDatabase>(relaxed = true),
            epubContentCache = mockk<EpubContentCache>(relaxed = true),
            appContext = context,
        )
    }

    /** 将给定 HTML 通过公开入口 loadChapterContent() 走完整链路转换为纯文本。 */
    private fun convert(html: String): String {
        val repository = mockk<ContentRepository>(relaxed = true)
        coEvery { repository.getChapterContent(any(), any()) } returns NovelChapterContent(html = html)
        return runBlocking {
            newLoader().loadChapterContent(repository = repository, chapter = newChapter())
        }
    }

    // ---------- 1. 纯文本段落 ----------

    @Test
    fun `plain text paragraphs pass through unchanged including blank line separators`() {
        val input = "第一段。\n\n第二段。"
        assertEquals(input, convert(input))
    }

    // ---------- 2. <br> 换行 ----------

    @Test
    fun `br br-slash and br-with-space are each converted to a single newline`() {
        val input = "第一行<br>第二行<br/>第三行<br />第四行"
        assertEquals("第一行\n第二行\n第三行\n第四行", convert(input))
    }

    @Test
    fun `br with attributes is normalized to a newline by the safelist`() {
        // Phase 4A 后：Jsoup Safelist 保留 <br>（丢弃其属性），换行不再丢失。
        val input = "第一行<br class=\"x\">第二行"
        assertEquals("第一行\n第二行", convert(input))
    }

    // ---------- 3. <p> 段落 ----------

    @Test
    fun `consecutive p elements are separated by a single newline not a double one`() {
        // 现状：</p> 换成 \n，<p...> 直接删除（不产生换行），因此两段之间只有单个 \n。
        val input = "<p>第一段</p><p>第二段</p>"
        assertEquals("第一段\n第二段\n", convert(input))
    }

    @Test
    fun `p opening tag attributes are stripped along with the tag`() {
        val input = "<p class=\"lead\" id=\"p1\">带属性段落</p>"
        assertEquals("带属性段落\n", convert(input))
    }

    // ---------- 4. 行内格式 <b>/<strong>/<em> ----------

    @Test
    fun `inline formatting tags are stripped while their inner text is preserved`() {
        val input = "这是<b>粗体</b>与<strong>强调</strong>和<em>斜体</em>文本。"
        assertEquals("这是粗体与强调和斜体文本。", convert(input))
    }

    // ---------- 5. <script> / <style> 块 ----------

    @Test
    fun `lowercase script block including its content is removed entirely`() {
        val input = "<p>正文</p><script>var x = 'pwn'; alert(1);</script><p>结尾</p>"
        assertEquals("正文\n结尾\n", convert(input))
    }

    @Test
    fun `uppercase SCRIPT block is removed entirely by the case-insensitive safelist`() {
        // Phase 4A 后：Jsoup Safelist 大小写不敏感地清理 <SCRIPT> 及其内容，不再泄漏。
        val input = "<p>前</p><SCRIPT>evil()</SCRIPT><p>后</p>"
        assertEquals("前\n后\n", convert(input))
    }

    @Test
    fun `script containing a closing tag inside a JS string is truncated early leaking the tail`() {
        // 现状：非贪婪 .*?</script> 在 JS 字符串内嵌的 </script> 处提前截断，残余 "; 泄漏为正文。
        val input = "<p>A</p><script>var s=\"</script>\";</script><p>B</p>"
        assertEquals("A\n\";B\n", convert(input))
    }

    @Test
    fun `style block is removed entirely like script`() {
        val input = "<p>首</p><style>.x { color: red }</style><p>尾</p>"
        assertEquals("首\n尾\n", convert(input))
    }

    // ---------- 6. <img src> ----------

    @Test
    fun `relative img src with no baseUrl is dropped by the safelist no placeholder`() {
        // Phase 4A 后：无 baseUrl 时相对 src 无法通过协议校验被 Safelist 丢弃，img 静默剥离。
        // （有 baseUrl 时由 NovelHtmlImageResolver 解析为绝对 URL 并在下载路径保留。）
        val input = "<p>插图：</p><img src=\"images/pic1.jpg\" alt=\"风景\"><p>继续</p>"
        assertEquals("插图：\n继续\n", convert(input))
    }

    @Test
    fun `img data-src relative values without baseUrl survive and are placeholder-rendered`() {
        // Phase 4A 后：Safelist 保留 data-src 属性（无协议校验），src 相对值被丢弃；
        // 无 src 时占位优先取 data-src（与 rewriteLocalImageSrc 的 Jsoup 语义一致）。
        val input = "<img src=\"a.jpg\" data-src=\"a.webp\"><img data-src=\"b.webp\" src=\"b.jpg\">"
        assertEquals("\n📷 [图片: a.webp]\n\n📷 [图片: b.webp]\n", convert(input))
    }

    @Test
    fun `img without src attribute produces no placeholder and is stripped silently`() {
        val input = "前<img>后"
        assertEquals("前后", convert(input))
    }

    // ---------- 7. 实体编码 & 超长文本 ----------

    @Test
    fun `named entities are decoded in fixed order nbsp lt gt amp quot apos`() {
        val input = "a &amp; b &lt;code&gt; &quot;q&quot; &apos;s&apos; &nbsp;x &amp;amp; &amp;lt; &#65;&#x42; &#x4E2D;"
        // 注意：实体解码发生在通用标签剥离之后，因此 &lt;code&gt; 解出的 <code> 不再被当作标签删除；
        // 且 &amp;amp; 只解码一层为 &amp;；&amp;lt; 由于 &lt; 替换已先执行，最终保持字面 &lt;（不再二次解码）。
        assertEquals("a & b <code> \"q\" 's'  x &amp; &lt; AB 中", convert(input))
    }

    @Test
    fun `double encoded entities are decoded only one level`() {
        assertEquals("&amp; | &lt;", convert("&amp;amp; | &amp;lt;"))
    }

    @Test
    fun `numeric and hex entities are decoded`() {
        assertEquals("AB中", convert("&#65;&#x42;&#x4E2D;"))
    }

    @Test
    fun `invalid hex and out-of-range numeric entities after the safelist`() {
        // Phase 4A 后：Jsoup 把越界数值实体 &#99999999; 归一化为 U+FFFD，非法十六进制保留原文。
        val input = "&#xZZ; &#99999999;"
        assertEquals("&#xZZ; \uFFFD", convert(input))
    }

    @Test
    fun `very long content is fully processed without truncation`() {
        val count = 3000
        val lines = List(count) { i ->
            "第${i}段：这是合法的中文正文内容，包含&amp;实体测试，用于验证超长文本不会截断。"
        }
        val input = lines.joinToString("") { "<p>$it</p>" }
        // entity &amp; is decoded to & by the pipeline, so the expectation must be too
        val expected = lines.joinToString("\n").replace("&amp;", "&")

        val result = convert(input)

        assertTrue(result.length > 100_000, "expected a long output, got ${result.length} chars")
        assertEquals(expected + "\n", result)
        assertFalse(result.contains("<p"), "opening <p> tags should all be stripped")
        assertFalse(result.contains("&amp;"), "&amp; should all be decoded")
        assertEquals(count, result.count { it == '&' }, "each paragraph keeps exactly one decoded &")
    }

    // ---------- 输出模型耦合：NovelParagraphSplitter ----------

    @Test
    fun `single newline between consecutive p paragraphs does not create a paragraph boundary`() {
        // 载荷（loader 对 <p>a</p><p>b</p> 的输出为 "a\nb"）经切分仍是同一段落，内部单换行保留。
        assertEquals(
            listOf(NovelParagraph(0, NovelParagraphType.TEXT, "第一段\n第二段")),
            NovelParagraphSplitter.split("第一段\n第二段"),
        )
    }

    @Test
    fun `image placeholder on its own line is typed IMAGE and skipped from translation`() {
        val content = "这里是插图说明：\n\n📷 [图片: images/pic1.jpg]\n\n然后继续阅读正文。"
        val paragraphs = NovelParagraphSplitter.split(content)
        assertEquals(
            listOf(
                NovelParagraph(0, NovelParagraphType.TEXT, "这里是插图说明："),
                NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片: images/pic1.jpg]"),
                NovelParagraph(2, NovelParagraphType.TEXT, "然后继续阅读正文。"),
            ),
            paragraphs,
        )
        assertEquals(
            listOf("这里是插图说明：", "然后继续阅读正文。"),
            NovelParagraphSplitter.buildTranslationInput(paragraphs),
        )
    }

    @Test
    fun `text joined to an image placeholder by a single newline is swallowed into the IMAGE paragraph and short text is typed IMAGE`() {
        // 现状：占位行后跟单换行 + 正文会把整块判为 IMAGE（正文从翻译输入中消失）；
        // 少于 4 个字符的段落也一律判为 IMAGE（跳过翻译）。
        val content = "这里是插图说明：\n\n📷 [图片: images/pic1.jpg]\n然后继续阅读正文。\n\n啊"
        val paragraphs = NovelParagraphSplitter.split(content)
        assertEquals(
            listOf(
                NovelParagraph(0, NovelParagraphType.TEXT, "这里是插图说明："),
                NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片: images/pic1.jpg]\n然后继续阅读正文。"),
                NovelParagraph(2, NovelParagraphType.IMAGE, "啊"),
            ),
            paragraphs,
        )
        assertEquals(listOf("这里是插图说明："), NovelParagraphSplitter.buildTranslationInput(paragraphs))
    }

    @Test
    fun `three or more consecutive newlines are folded to a double newline before splitting`() {
        val paragraphs = NovelParagraphSplitter.split("甲\n\n\n\n乙")
        assertEquals(
            listOf(
                NovelParagraph(0, NovelParagraphType.IMAGE, "甲"),
                NovelParagraph(1, NovelParagraphType.IMAGE, "乙"),
            ),
            paragraphs,
        )
    }
}
