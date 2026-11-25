package org.skepsun.kototoro.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranslationDictionaryPolicyTest {

    @Test
    fun `parses optional ai dictionary and result sections`() {
        val parsed = AiTranslationOutputParser.parse(
            """
            [dictionary]
            Doctor Smith -> 史密斯医生
            [result]
            史密斯医生走进房间。
            """.trimIndent(),
        )

        assertEquals("史密斯医生走进房间。", parsed.translatedText)
        assertEquals(listOf(DictPair("Doctor Smith", "史密斯医生")), parsed.discoveredPairs)
    }

    @Test
    fun `selects exact and contained terms from a source paragraph`() {
        val pairs = listOf(
            DictPair("Doctor Smith", "史密斯医生"),
            DictPair("Smith", "史密斯"),
            DictPair("Alice", "爱丽丝"),
        )

        assertEquals(
            listOf(DictPair("Doctor Smith", "史密斯医生"), DictPair("Smith", "史密斯")),
            TranslationDictionaryPolicy.selectRelevant("Doctor Smith arrived", pairs),
        )
    }

    @Test
    fun `merges discovered pairs without blank or duplicate originals`() {
        val merged = TranslationDictionaryPolicy.mergeDiscovered(
            existing = listOf(DictPair("Alice", "爱丽丝")),
            discovered = listOf(
                DictPair(" Alice ", "爱丽丝"),
                DictPair("Bob", "鲍勃"),
                DictPair("", "无效"),
                DictPair("Carol", ""),
            ),
        )

        assertEquals(
            listOf(DictPair("Alice", "爱丽丝"), DictPair("Bob", "鲍勃")),
            merged,
        )
    }
    @Test
    fun `manual replacements win and removed terms stay out of future prompts`() {
        val existing = listOf(DictPair("Alice", "爱丽丝"), DictPair("Bob", "鲍勃"))
        val edited = TranslationDictionaryPolicy.applyManualEdits(existing, listOf(DictPair("Alice", "艾莉丝")))
        val afterDiscovery = TranslationDictionaryPolicy.mergeDiscovered(edited, existing)

        assertEquals(
            listOf(DictPair("Alice", "艾莉丝")),
            TranslationDictionaryPolicy.selectRelevant("Alice and Bob", afterDiscovery),
        )
    }

}
