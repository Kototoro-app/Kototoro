package org.skepsun.kototoro.core.model.jsonsource

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegadoBookSourceTest {

    @Test
    fun `source rules survive the shared catalog serialization`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val source = json.decodeFromString<LegadoBookSource>(
            """{"bookSourceName":"Example","bookSourceUrl":"https://example.test",
                "searchUrl":"/search?key={{key}}","ruleSearch":{"bookList":"class.book","name":"tag.a@text"},
                "ruleToc":{"chapterList":"class.chapter","chapterUrl":"tag.a@href"},
                "ruleContent":{"content":"id.content@html"},"unknownField":true}""",
        )

        assertEquals("/search?key={{key}}", source.searchUrl)
        assertEquals("class.book", source.ruleSearch?.bookList)
        assertEquals("tag.a@href", source.ruleToc?.chapterUrl)
        assertEquals("id.content@html", source.ruleContent?.content)
        assertEquals(source, json.decodeFromString<LegadoBookSource>(json.encodeToString(source)))
    }

    @Test
    fun `catalog decoding and identifiers match Android source conventions`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val sources = LegadoSourceCatalog.decode(
            """[{"bookSourceName":"Example","bookSourceUrl":"https://example.test","unknown":1}]""",
            json,
        )
        val url = sources.single().bookSourceUrl
        val hash = url.hashCode().toUInt().toString(16).uppercase()

        assertEquals("JSON_LEGADO_$hash", LegadoSourceCatalog.sourceId(url, 0))
        assertEquals("JSON_LEGADO_M_$hash", LegadoSourceCatalog.sourceId(url, 2))
    }
}
