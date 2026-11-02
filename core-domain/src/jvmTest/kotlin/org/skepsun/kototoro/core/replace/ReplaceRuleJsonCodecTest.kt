package org.skepsun.kototoro.core.replace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplaceRuleJsonCodecTest {

    @Test
    fun `imports legado legacy field aliases`() {
        val json = """
            {
              "id": 42,
              "regex": "广告[^\\n]*",
              "replaceSummary": "移除广告",
              "replacement": "",
              "isRegex": true,
              "useTo": "content",
              "enable": false,
              "serialNumber": 7
            }
        """.trimIndent()

        val rule = ReplaceRuleJsonCodec.decode(json).single()

        assertEquals(42L, rule.id)
        assertEquals("广告[^\\n]*", rule.pattern)
        assertEquals("移除广告", rule.name)
        assertEquals(ReplaceRule.Scope.CONTENT, rule.scope)
        assertFalse(rule.isEnabled)
        assertTrue(rule.isRegex)
        assertEquals(7, rule.order)
    }

    @Test
    fun `imports a canonical legado rule object and exports canonical field names`() {
        val rule = ReplaceRuleJsonCodec.decode(
            """
                {
                  "id": 9,
                  "name": "空白归一",
                  "group": "排版",
                  "pattern": "[ \\t]+",
                  "replacement": " ",
                  "scope": "source-a",
                  "scopeTitle": false,
                  "scopeContent": true,
                  "excludeScope": "source-b",
                  "isEnabled": true,
                  "isRegex": true,
                  "timeoutMillisecond": 1500,
                  "order": 3
                }
            """.trimIndent(),
        ).single()

        assertEquals("source-a", rule.scopeFilter)
        assertEquals("source-b", rule.excludeScope)
        assertEquals(1500L, rule.timeoutMillisecond)

        val exported = ReplaceRuleJsonCodec.encode(listOf(rule))
        assertTrue(exported.contains("\"pattern\""))
        assertTrue(exported.contains("\"scope\""))
        assertTrue(exported.contains("\"order\""))
        assertFalse(exported.contains("\"regex\""))
    }
}
