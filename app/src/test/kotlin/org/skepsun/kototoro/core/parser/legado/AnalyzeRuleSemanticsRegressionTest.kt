package org.skepsun.kototoro.core.parser.legado

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import kotlin.coroutines.EmptyCoroutineContext

class AnalyzeRuleSemanticsRegressionTest : FunSpec({

    test("JS 模式下应继续解析 @get 占位符") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "'prefix-' + 'abc'"
                "prefix-abc"
            },
        ).apply {
            putVariable("token", "abc")
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("<js>'prefix-' + '@get:{token}'</js>") shouldBe "prefix-abc"
    }

    test("JS 模式下不应对 @get 替换结果再做二次占位符展开") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "'prefix-' + '@get:{name}'"
                "prefix-@get:{name}"
            },
        ).apply {
            putVariable("token", "@get:{name}")
            putVariable("name", "Alice")
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("<js>'prefix-' + '@get:{token}'</js>") shouldBe "prefix-@get:{name}"
    }

    test("JS 模式下 NativeObject 直取场景应与当前 MD3 一致返回拼装后的规则串") {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            val obj = context.newObject(scope)
            ScriptableObject.putProperty(obj, "id", "42")

            val analyzer = AnalyzeRule(
                content = obj,
                runtimeContext = TestLegadoRuleRuntimeContext(
                    jsEvaluator = { script, _ ->
                        when (script) {
                            "result.id" -> "42"
                            else -> error("Unexpected script: $script")
                        }
                    },
                ),
                baseUrl = "https://example.com",
            )

            analyzer.getString("<js>\"book-\" + {{result.id}}</js>") shouldBe "\"book-\" + 42"
        } finally {
            Context.exit()
        }
    }

    test("{{data.name}} 应与 MD3 一样按 JS 表达式求值而不是按规则解释") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "data.name"
                "Alpha"
            },
        ).apply {
            putVariableAny("data", mapOf("name" to "Alpha"))
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("{{data.name}}") shouldBe "Alpha"
    }

    test("裸 \$var 不应仅因 $ 前缀被误判为 JSONPath 规则") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "\$token"
                "Alpha"
            },
        )
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("{{\$token}}") shouldBe "Alpha"
    }

    test("replaceFirst 在正则无匹配时应与当前 MD3 兼容") {
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        analyzer.getString("nomatch##foo(\\d+)##fallback##1", mContent = "plain text") shouldBe ""
    }

    test("##regex##replacement 遇到灾难性回溯时在时限内放弃并保留原文") {
        val analyzer = AnalyzeRule(
            content = "<div>${"a".repeat(64)}!</div>",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        analyzer.getString("div@text##a+##X") shouldBe "X!"
        val started = System.nanoTime()
        analyzer.getString("div@text##^(a|aa)+\\1\$##X") shouldBe "${"a".repeat(64)}!"
        analyzer.getString("div@text##^(a|aa)+\\1\$##X###") shouldBe ""
        ((System.nanoTime() - started) / 1_000_000 < 10_000) shouldBe true
    }

    test("规则前缀包含 ## 时 {{}} 不应把整条规则切入 Regex 模式，最终仍按默认规则求值") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "token"
                "Alpha"
            },
        )
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("prefix##suffix{{token}}") shouldBe ""
    }

    test("规则前缀不含 ## 时 {{}} 应把整条规则切入 Regex 模式") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, _ ->
                script shouldBe "token"
                "Alpha"
            },
        )
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("prefix{{token}}suffix") shouldBe "prefixAlphasuffix"
    }

    test("@put 应支持 Legado 常见的宽松 JSON 写法并正确解析规则值") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val analyzer = AnalyzeRule(
            content = "<div>Alpha</div>",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.getString("@put:{token:'div@text'}div@text") shouldBe "Alpha"
        analyzer.get("token") shouldBe "Alpha"
    }

    test("内容为空时应与 MD3 一致抛出 AssertionError") {
        val error = shouldThrow<AssertionError> {
            AnalyzeRule(
                content = null,
                runtimeContext = TestLegadoRuleRuntimeContext(),
                baseUrl = "https://example.com",
            )
        }

        error.message shouldBe "内容不可空（Content cannot be null）"
    }

    test("零参 AnalyzeRule 应支持先构造后通过 mContent 解析，兼容 MD3 无 content 起手路径") {
        val analyzer = AnalyzeRule().setCoroutineContext(EmptyCoroutineContext)

        analyzer.getString("div@text", mContent = "<div>Alpha</div>") shouldBe "Alpha"
    }

    test("零参 AnalyzeRule 应支持后续 setContent 链路而不放松 null 校验") {
        val analyzer = AnalyzeRule()

        analyzer.setContent("<div>Beta</div>", "https://example.com/base/")
        analyzer.getString("div@text") shouldBe "Beta"

        val error = shouldThrow<AssertionError> {
            analyzer.setContent(null)
        }
        error.message shouldBe "内容不可空（Content cannot be null）"
    }

    test("AnalyzeRule source 兼容构造应按 MD3 将首参视为 ruleData 并暴露 sourceTag") {
        val source = LegadoBookSource(
            bookSourceName = "源A",
            bookSourceUrl = "https://source.example.com",
        )
        val analyzer = AnalyzeRule(
            ruleData = BookInfo(
                bookUrl = "https://source.example.com/book/1",
                name = "构造书籍",
                author = "作者甲",
            ),
            source = source,
        )

        analyzer.getTag() shouldBe "源A"
        analyzer.get("bookName") shouldBe "构造书籍"
        analyzer.get("bookUrl") shouldBe "https://source.example.com/book/1"
    }

    test("AnalyzeRule source 兼容构造应允许后续 setContent 继续使用 source 默认 baseUrl") {
        val source = LegadoBookSource(
            bookSourceName = "源A",
            bookSourceUrl = "https://source.example.com",
        )
        val analyzer = AnalyzeRule(
            ruleData = BookInfo(
                bookUrl = "https://source.example.com/book/1",
                name = "构造书籍",
                author = "作者甲",
            ),
            source = source,
        ).setContent("<a href=\"/detail/1\">next</a>")

        analyzer.getString("a@href", isUrl = true) shouldBe "https://source.example.com/detail/1"
    }

    test("getString 默认应与 MD3 一致执行 HTML4 实体反解码") {
        val analyzer = AnalyzeRule(
            content = "<div>&amp;nbsp;&lt;b&gt;Tom &amp; Jerry&lt;/b&gt;</div>",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        analyzer.getString("div@text") shouldBe "\u00A0<b>Tom & Jerry</b>"
    }

    test("setContent setBaseUrl 与 setNextChapterUrl 应保持链式调用语义") {
        val analyzer = AnalyzeRule(
            content = "<a href=\"chapter/2\">next</a>",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com/book/1/",
        )

        analyzer
            .setContent("<a href=\"chapter/3\">next</a>")
            .setBaseUrl("https://example.com/book/2/")
            .setNextChapterUrl("/chapter/4")

        analyzer.get("nextChapterUrl") shouldBe "/chapter/4"
        analyzer.getString("a@href", isUrl = true) shouldBe "https://example.com/book/2/chapter/3"
    }

    test("setRuntimeBook 与 setRuntimeChapter 应提供链式 runtime 上下文注入") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer
            .setRuntimeBook(
                org.skepsun.kototoro.core.javascript.BookInfo(
                    bookUrl = "https://example.com/book/1",
                    name = "测试书",
                    author = "作者甲",
                ),
            )
            .setRuntimeChapter(
                org.skepsun.kototoro.core.javascript.ChapterInfo(
                    chapterUrl = "https://example.com/book/1/1",
                    name = "第一章",
                    index = 1,
                ),
            )

        analyzer.get("bookName") shouldBe "测试书"
        analyzer.get("title") shouldBe "第一章"
    }

    test("公开 splitSourceRule 应可复用预拆分规则并保持 MD3 风格调用") {
        val analyzer = AnalyzeRule(
            content = "<div><span>Alpha</span><span>Beta</span></div>",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        val ruleList = analyzer.splitSourceRule("span@text")

        ruleList.shouldHaveSize(1)
        analyzer.getStringList(ruleList) shouldBe listOf("Alpha", "Beta")
        analyzer.getString(ruleList) shouldBe "Alpha\nBeta"
    }

    test("setRuleData 传入 RuleDataInterface 时应参与变量读写") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val ruleData = RuleData()
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(ruleData)

        analyzer.put("token", "Alpha") shouldBe "Alpha"
        ruleData.getVariable("token") shouldBe "Alpha"
        analyzer.get("token") shouldBe "Alpha"
    }

    test("显式 setRuleData 变量应优先于 runtime 全局变量，贴近 MD3 ruleData 语义") {
        val runtimeContext = TestLegadoRuleRuntimeContext().apply {
            putVariable("token", "runtime")
        }
        val ruleData = RuleData().apply {
            putVariable("token", "ruleData")
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(ruleData)

        analyzer.get("token") shouldBe "ruleData"
    }

    test("chapter 变量仍应优先于显式 setRuleData，贴近 MD3 优先级") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val chapter = ChapterInfo(
            chapterUrl = "https://example.com/chapter/3",
            name = "第三章",
            index = 3,
        ).apply {
            putVariable("token", "chapter")
        }
        val ruleData = RuleData().apply {
            putVariable("token", "ruleData")
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )
            .setChapter(chapter)
            .setRuleData(ruleData)

        analyzer.get("token") shouldBe "chapter"
    }

    test("setRuleData 传入 BookInfo 时应对齐到当前 runtime book") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(
            BookInfo(
                bookUrl = "https://example.com/book/42",
                name = "运行时书籍",
                author = "作者乙",
            ),
        )

        analyzer.get("bookName") shouldBe "运行时书籍"
        analyzer.put("custom", "v1")
        runtimeContext.getBook()?.getVariable("custom") shouldBe "v1"
    }

    test("setRuleData 传入带 getVariable putVariable 的对象时应兼容反射适配") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val holder = LegacyRuleDataHolder()
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(holder)

        analyzer.put("rssKey", "rssValue")

        holder.getVariable("rssKey") shouldBe "rssValue"
        analyzer.get("rssKey") shouldBe "rssValue"
    }

    test("兼容 setChapter 与 setCoroutineContext 入口应可链式调用") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer
            .setCoroutineContext(EmptyCoroutineContext)
            .setChapter(
                ChapterInfo(
                    chapterUrl = "https://example.com/chapter/7",
                    name = "兼容章节",
                    index = 7,
                ),
            )

        analyzer.get("chapterUrl") shouldBe "https://example.com/chapter/7"
        analyzer.get("title") shouldBe "兼容章节"
    }

    test("setRuleData 为 book-like 对象时 JS 侧应暴露 book，贴近 MD3") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { _, context ->
                val book = requireNotNull(context.getVariable("book"))
                val name = LegadoReflectiveAccess.readProperty(book, "name")
                val bookUrl = LegadoReflectiveAccess.readProperty(book, "bookUrl")
                "$name|$bookUrl"
            },
        )
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(
            LegacyBookLikeRuleData(
                bookUrl = "https://example.com/book/99",
                name = "搜索书籍",
            ),
        )

        analyzer.evalJS("ignored", "seed") shouldBe "搜索书籍|https://example.com/book/99"
    }

    test("AnalyzeRule(ruleData, source) 应在 JS 侧暴露 book，贴近 MD3 构造语义") {
        val analyzer = AnalyzeRule(
            ruleData = LegacyBookLikeRuleData(
                bookUrl = "https://source.example.com/book/9",
                name = "构造书籍",
            ),
            source = LegadoBookSource(
                bookSourceName = "源构造",
                bookSourceUrl = "https://source.example.com",
            ),
        )

        analyzer.setContent("ignored")
        analyzer.evalJS("ignored", "seed").toString() shouldBe "seed"
        analyzer.get("bookName") shouldBe "构造书籍"
        analyzer.get("bookUrl") shouldBe "https://source.example.com/book/9"
        analyzer.getTag() shouldBe "源构造"
    }

    test("AnalyzeRule(null, source) 应尽量对齐 MD3 走 source 自身的 put/get 语义") {
        val source = LegacyRuleSource(
            tag = "RSS-Source",
            bookSourceUrl = "https://rss.example.com",
        )
        val analyzer = AnalyzeRule(
            ruleData = null,
            source = source,
        )

        analyzer.put("token", "Alpha") shouldBe "Alpha"
        analyzer.get("token") shouldBe "Alpha"
        source.get("token") shouldBe "Alpha"
        analyzer.getTag() shouldBe "RSS-Source"
    }

    test("AnalyzeRule put 在 chapter 存在时不应再向 source 扩散写入，贴近 MD3 首层命中语义") {
        val runtimeContext = TestLegadoRuleRuntimeContext()
        val chapter = ChapterInfo(
            chapterUrl = "https://example.com/chapter/9",
            name = "第九章",
            index = 9,
        )
        val source = LegacyRuleSource(
            tag = "RSS-Source",
            bookSourceUrl = "https://rss.example.com",
        )
        runtimeContext.setChapter(chapter)
        runtimeContext.setSource(source, "RSS-Source")
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.put("token", "chapterOnly") shouldBe "chapterOnly"
        chapter.getVariable("token") shouldBe "chapterOnly"
        source.get("token") shouldBe ""
        runtimeContext.getSourceVariable("token") shouldBe ""
    }

    test("setRuleData 为 rss-like 对象时 JS 侧应暴露 rssArticle，贴近 MD3") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { _, context ->
                val article = requireNotNull(context.getVariable("rssArticle"))
                val title = LegadoReflectiveAccess.readProperty(article, "title")
                val link = LegadoReflectiveAccess.readProperty(article, "link")
                "$title|$link"
            },
        )
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        ).setRuleData(
            LegacyRssArticleLikeRuleData(
                origin = "https://rss.example.com",
                link = "/post/1",
                title = "文章甲",
            ),
        )

        analyzer.evalJS("ignored", "seed") shouldBe "文章甲|/post/1"
    }

    test("setRedirectUrl 应优先参与相对地址解析并忽略 data url") {
        val analyzer = AnalyzeRule(
            content = "<a href=\"../images/1.jpg\">img</a>",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com/book/1/chapters/current.html",
        )

        analyzer.setRedirectUrl("https://cdn.example.com/assets/books/1/index.html")
        analyzer.getString("a@href", isUrl = true) shouldBe "https://cdn.example.com/assets/books/images/1.jpg"

        analyzer.setRedirectUrl("data:text/plain;base64,SGVsbG8=")?.toString() shouldBe
            "https://cdn.example.com/assets/books/1/index.html"
        analyzer.getString("a@href", isUrl = true) shouldBe "https://cdn.example.com/assets/books/images/1.jpg"
    }

    test("setRuleName 与 getTag 应对齐 MD3 的调试标签语义") {
        val analyzer = AnalyzeRule(
            content = "x",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        analyzer.getTag().shouldBeNull()
        analyzer.setRuleName("bookInfo.init")
        analyzer.getTag() shouldBe "bookInfo.init"
        analyzer.setRuleName("")
        analyzer.getTag() shouldBe "bookInfo.init"
    }

    test("AnalyzeRule evalJS 应把 java 绑定为当前 AnalyzeRule 并保留 source/tag 语义") {
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { _, context ->
                val java = requireNotNull(context.getAllVariables()["java"])
                val source = java.javaClass.getMethod("getSource").invoke(java)
                val tag = java.javaClass.getMethod("getTag").invoke(java)
                val chapterName = java.javaClass.getMethod("get", String::class.java).invoke(java, "chapterName")
                "$source|$tag|$chapterName"
            },
        ).apply {
            setSource(sourceObject = "source-object", sourceTag = "Source-A")
            setChapter(
                org.skepsun.kototoro.core.javascript.ChapterInfo(
                    chapterUrl = "https://example.com/c/1",
                    name = "第一章",
                    index = 1,
                ),
            )
        }
        val analyzer = AnalyzeRule(
            content = "x",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.evalJS("ignored", "seed") shouldBe "source-object|Source-A|第一章"
    }

    test("AnalyzeRule ajax 应走当前 runtime 的请求执行与 JS 模板变量展开") {
        var capturedPlan: LegadoRequestPlan? = null
        val runtimeContext = TestLegadoRuleRuntimeContext(
            jsEvaluator = { script, context ->
                if (script == "token") {
                    context.getVariable("token")
                } else {
                    null
                }
            },
        ).apply {
            putVariable("token", "abc")
            setHttpExecutor(
                object : LegadoHttpExecutor {
                    override suspend fun execute(plan: LegadoRequestPlan): LegadoHttpResponse {
                        capturedPlan = plan
                        return LegadoHttpResponse(
                            url = plan.url,
                            body = "payload",
                            code = 200,
                        )
                    }
                },
            )
        }
        val analyzer = AnalyzeRule(
            content = "ignored",
            runtimeContext = runtimeContext,
            baseUrl = "https://example.com",
        )

        analyzer.ajax("https://example.com/api/{{token}}") shouldBe "payload"
        capturedPlan!!.url shouldBe "https://example.com/api/abc"
    }

    test("reGetBook 与 refreshTocUrl 在非 preUpdateJs 场景应抛 MD3 风格无堆栈异常") {
        val analyzer = AnalyzeRule(
            content = "x",
            runtimeContext = TestLegadoRuleRuntimeContext(),
            baseUrl = "https://example.com",
        )

        val reGetError = shouldThrow<NoStackTraceException> {
            analyzer.reGetBook()
        }
        reGetError.message shouldBe "只能在 preUpdateJs 中调用"
        reGetError.stackTrace.size shouldBe 0

        val refreshError = shouldThrow<NoStackTraceException> {
            analyzer.refreshTocUrl()
        }
        refreshError.message shouldBe "只能在 preUpdateJs 中调用"
        refreshError.stackTrace.size shouldBe 0
    }
})

private class LegacyRuleDataHolder {
    private val variables = linkedMapOf<String, String>()

    fun getVariable(key: String): String? = variables[key]

    fun putVariable(key: String, value: String?) {
        if (value == null) {
            variables.remove(key)
        } else {
            variables[key] = value
        }
    }

    fun getVariableMap(): Map<String, String> = variables.toMap()
}

private class LegacyRuleSource(
    private val tag: String,
    val bookSourceUrl: String,
) {
    private val variables = linkedMapOf<String, String>()

    fun put(key: String, value: String) {
        variables[key] = value
    }

    fun get(key: String): String = variables[key].orEmpty()

    fun getTag(): String = tag
}

data class LegacyBookLikeRuleData(
    val bookUrl: String,
    val name: String,
    private val variables: LinkedHashMap<String, String> = linkedMapOf(),
) : RuleDataInterface {
    override fun getVariable(key: String): String? = variables[key]

    override fun putVariable(key: String, value: String?): String? {
        val previous = variables[key]
        if (value == null) {
            variables.remove(key)
        } else {
            variables[key] = value
        }
        return previous
    }

    override fun getVariableMap(): Map<String, String> = variables.toMap()

    override fun clearVariables() {
        variables.clear()
    }
}

data class LegacyRssArticleLikeRuleData(
    val origin: String,
    val link: String,
    val title: String,
    private val variables: LinkedHashMap<String, String> = linkedMapOf(),
) : RuleDataInterface {
    override fun getVariable(key: String): String? = variables[key]

    override fun putVariable(key: String, value: String?): String? {
        val previous = variables[key]
        if (value == null) {
            variables.remove(key)
        } else {
            variables[key] = value
        }
        return previous
    }

    override fun getVariableMap(): Map<String, String> = variables.toMap()

    override fun clearVariables() {
        variables.clear()
    }
}
