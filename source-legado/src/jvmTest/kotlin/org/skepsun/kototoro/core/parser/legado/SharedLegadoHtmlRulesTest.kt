package org.skepsun.kototoro.core.parser.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SharedLegadoHtmlRulesTest {

    @Test
    fun `extracts chapter links and reverse indexed titles with Android rules`() {
        val html = """<ul><li><a href="/1">First</a></li><li><a href="/2">Second</a></li></ul>"""
        val analyzer = AnalyzeByJSoup(html)

        assertEquals(listOf("Second", "First"), analyzer.getStringList("tag.li[-1:0]@tag.a@text"))
        assertEquals(listOf("/1", "/2"), analyzer.getStringList("tag.li@tag.a@href"))
    }
}
