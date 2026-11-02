package org.skepsun.kototoro.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryRuleJsonCodecTest {

    @Test
    fun `decodes legado dictionary rule array`() {
        val rules = DictionaryRuleJsonCodec.decode(
            """
            [{
              "name": "海词英文",
              "urlRule": "https://apii.dict.cn/mini.php?q={{key}}",
              "showRule": "tag.body@all",
              "enabled": true,
              "sortNumber": 3
            }]
            """.trimIndent(),
        )

        assertEquals(1, rules.size)
        assertEquals("海词英文", rules.single().name)
        assertEquals("https://apii.dict.cn/mini.php?q={{key}}", rules.single().urlRule)
        assertEquals("tag.body@all", rules.single().showRule)
        assertTrue(rules.single().enabled)
        assertEquals(3, rules.single().sortNumber)
    }

    @Test
    fun `accepts a single imported rule object`() {
        val rules = DictionaryRuleJsonCodec.decode(
            """
            {
              "name": "本地词典",
              "urlRule": "data:text/plain,{{key}}",
              "showRule": ""
            }
            """.trimIndent(),
        )

        assertEquals(listOf("本地词典"), rules.map { it.name })
        assertEquals(0, rules.single().sortNumber)
        assertTrue(rules.single().enabled)
    }
}
