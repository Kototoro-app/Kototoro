package org.skepsun.kototoro.core.parser.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.model.jsonsource.BookInfoRule
import org.skepsun.kototoro.core.model.jsonsource.SearchRule
import org.skepsun.kototoro.core.model.jsonsource.TocRule
import org.skepsun.kototoro.core.model.jsonsource.ContentRule
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.book.BookInfo as LegadoBookInfo
import org.skepsun.kototoro.core.parser.legado.book.BookList
import org.skepsun.kototoro.core.parser.legado.book.BookChapterList
import org.skepsun.kototoro.core.parser.legado.book.BookContent
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoVariableStore
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoRuntimeState
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoListRuntime
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SharedLegadoScriptContextTest {

    @Test
    fun `book and chapter variables survive normal runtime updates`() {
        val book = BookInfo(bookUrl = "https://example.test/book", name = "Original")
        val chapter = ChapterInfo(chapterUrl = "https://example.test/1", name = "First", index = 1)
        book.setProperty("name", "Updated")
        book.putVariable("token", "abc")
        chapter.putVariable("page", "2")

        assertEquals("Updated", book.name)
        assertEquals("abc", book.getVariable("token"))
        assertEquals("2", chapter.getVariable("page"))
    }

    @Test
    fun `source variables persist while legacy keys remain readable`() {
        val store = MemoryStore()
        val first = StandaloneLegadoRuntimeState(sourceKey = "source", variableStore = store)
        first.putVariable("token", "abc")
        first.putVariable("page", 3)

        val restored = StandaloneLegadoRuntimeState(sourceKey = "source", variableStore = store)
        assertEquals("abc", restored.getVariable("token"))
        assertEquals("legacy", restored.getVariable("old"))
        assertNull(restored.getVariable("page"))
    }

    @Test
    fun `runtime context passes source and variables to the host script engine`() {
        val source = LegadoBookSource(bookSourceName = "Example", bookSourceUrl = "https://example.test")
        val engine = RecordingEngine()
        val context = StandaloneLegadoRuleRuntimeContext(jsEngine = engine, source = source)
        context.putVariable("token", "abc")

        assertEquals("abc", context.evalJs("token", null, source.bookSourceUrl))
        assertEquals(source, engine.lastContext?.source)
        assertEquals("abc", engine.lastContext?.getVariable("token"))
    }

    @Test
    fun `shared runtime builds a search request and parses its response`() {
        val source = LegadoBookSource(bookSourceName = "Example", bookSourceUrl = "https://example.test")
        val runtime = StandaloneLegadoListRuntime(jsEngine = RecordingEngine(), source = source)
        val request = runtime.prepareRequest("/search?q={{key}}", key = "novel")

        assertEquals("https://example.test/search?q=novel", request.requestPlan.url)
        assertEquals(
            listOf("First", "Second"),
            runtime.parseStringList(
                content = "<ul><li>First</li><li>Second</li></ul>",
                rule = "tag.li@text",
                runtimeContext = request.runtimeContext,
                baseUrl = source.bookSourceUrl,
            ),
        )
    }

    @Test
    fun `shared book parsers turn search and details into a work`() {
        val source = object : ContentSource {
            override val name = "JSON_LEGADO_TEST"
            override val locale = "zh"
            override val contentType = ContentType.NOVEL
        }
        val config = LegadoBookSource(
            bookSourceName = "Example",
            bookSourceUrl = "https://example.test",
            ruleSearch = SearchRule(bookList = "class.book", name = "tag.a@text", bookUrl = "tag.a@href"),
            ruleBookInfo = BookInfoRule(intro = "class.intro@text", tocUrl = "class.toc@href"),
            ruleToc = TocRule(chapterList = "class.chapter", chapterName = "tag.a@text", chapterUrl = "tag.a@href"),
            ruleContent = ContentRule(content = "class.content@html"),
        )
        val context = StandaloneLegadoRuleRuntimeContext(jsEngine = RecordingEngine(), source = config)
        val books = BookList.parseWithRuntimeContext(
            """<div class="book"><a href="/book/1">First</a></div>""",
            config.bookSourceUrl,
            source,
            config,
            isSearch = true,
            runtimeContext = context,
        )
        val details = LegadoBookInfo.parseWithRuntimeContext(
            manga = books.single(),
            content = """<div class="intro">Description</div><a class="toc" href="/book/1/toc">TOC</a>""",
            baseUrl = "https://example.test/book/1",
            config = config,
            runtimeContext = context,
        )

        assertEquals("First", details.manga.title)
        assertEquals("https://example.test/book/1", details.manga.url)
        assertEquals("Description", details.manga.description)
        assertEquals("https://example.test/book/1/toc", details.tocUrl)

        val toc = BookChapterList.parseWithRuntimeContext(
            """<li class="chapter"><a href="/book/1/1">Chapter 1</a></li>""",
            details.tocUrl!!,
            source,
            config,
            context,
        )
        assertEquals("https://example.test/book/1/1", toc.chapters.single().url)
        val page = BookContent.parseNovelPageWithRuntimeContext(
            """<div class="content"><p>First paragraph</p><p>Second paragraph</p></div>""",
            toc.chapters.single().url,
            source,
            config,
            context,
        )
        assertEquals("First paragraph\nSecond paragraph", page.content)
    }

    private class RecordingEngine : JavaScriptEngine {
        var lastContext: JavaScriptContext? = null
        override fun execute(script: String, context: JavaScriptContext): Any? = evaluate(script, context)
        override fun evaluate(expression: String, context: JavaScriptContext): Any? {
            lastContext = context
            return context.getVariable(expression)
        }
        override fun registerGlobalObject(name: String, obj: Any) = Unit
        override fun dispose() = Unit
    }

    private class MemoryStore : LegadoVariableStore {
        private var source: String? = null
        override fun getVariable(sourceKey: String, key: String): String? = if (key == "old") "legacy" else null
        override fun getSourceVariable(sourceKey: String): String? = source
        override fun putSourceVariable(sourceKey: String, value: String?) { source = value }
        override fun getLoginInfo(sourceKey: String): String? = null
        override fun putLoginInfo(sourceKey: String, value: String?) = Unit
        override fun getLoginHeader(sourceKey: String): String? = null
        override fun putLoginHeader(sourceKey: String, value: String?) = Unit
    }
}
