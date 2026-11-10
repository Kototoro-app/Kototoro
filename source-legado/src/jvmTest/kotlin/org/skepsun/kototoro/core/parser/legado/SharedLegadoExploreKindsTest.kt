package org.skepsun.kototoro.core.parser.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext

class SharedLegadoExploreKindsTest {
    private val source = LegadoBookSource(bookSourceName = "Example", bookSourceUrl = "https://example.test")

    private fun parse(exploreUrl: String?, script: String? = null) = LegadoExploreKinds.parse(
        exploreUrl,
        StandaloneLegadoRuleRuntimeContext(jsEngine = FixedEngine(script), source = source),
        source.bookSourceUrl,
    )

    @Test
    fun `text catalogs split on newlines and double ampersands`() {
        assertEquals(
            listOf(ExploreKind("Latest", "/latest?p={{page}}"), ExploreKind("Heading", null), ExploreKind("Rank", "/rank")),
            parse("Latest::/latest?p={{page}}\nHeading&&Rank::/rank"),
        )
        assertEquals(emptyList<ExploreKind>(), parse(" "))
    }

    @Test
    fun `json catalogs accept single quotes extra keys and unescaped template quotes`() {
        assertEquals(
            listOf(ExploreKind("Latest", "/latest"), ExploreKind("Heading", null)),
            parse("[{'title':'Latest','url':'/latest','style':{'layout_flexGrow':1}},{'title':'Heading'}]"),
        )
        assertEquals(
            listOf(ExploreKind("Mine", "{{java.get(\"host\")}}/mine")),
            parse("""[{"title":"Mine","url":"{{java.get("host")}}/mine"}]"""),
        )
    }

    @Test
    fun `scripted catalogs use the text the script returns`() {
        assertEquals(listOf(ExploreKind("Made", "/made")), parse("@js:build()", script = "Made::/made"))
    }

    private class FixedEngine(private val result: String?) : JavaScriptEngine {
        override fun execute(script: String, context: JavaScriptContext): Any? = result
        override fun evaluate(expression: String, context: JavaScriptContext): Any? = result
        override fun registerGlobalObject(name: String, obj: Any) = Unit
        override fun dispose() = Unit
    }
}
