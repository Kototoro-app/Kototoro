package org.skepsun.kototoro.core.parser.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SharedLegadoStructuredRulesTest {

    @Test
    fun `json path retains Android list and null semantics`() {
        val analyzer = AnalyzeByJsonPath("""{"items":[1,null,"x"],"value":null}""")

        assertEquals(listOf("1", "null", "x"), analyzer.getStringList("$.items[*]"))
        assertEquals(emptyList<String>(), analyzer.getStringList("$.value"))
        assertNull(analyzer.getList("$.value"))
    }

    @Test
    fun `xpath nodes feed into the shared html rules`() {
        val analyzer = AnalyzeByXPath("""<div class="book"><span class="name">Example</span></div>""")
        val book = analyzer.getElements("//div[@class='book']")!!.single()

        assertEquals(listOf("Example"), AnalyzeByJSoup(book).getStringList(".name@text"))
        assertEquals("Example", analyzer.getString("//h1/text()||//span[@class='name']/text()"))
    }

    @Test
    fun `regex rules keep groups and honor expired deadlines`() {
        assertEquals(listOf("Chapter 12", "12"), AnalyzeByRegex.getElement("Chapter 12", arrayOf("Chapter (\\d+)")))
        assertNull(BoundedRegex.withRegexDeadline("a".repeat(2_048), System.nanoTime() - 1_000_000_000L) { input ->
            Regex("a+").find(input)
        })
    }
}
