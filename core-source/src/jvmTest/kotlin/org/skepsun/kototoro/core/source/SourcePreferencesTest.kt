package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourcePreferencesTest {
    @Test
    fun `JSON retains native value types and exact long and float bits`() {
        val values: Map<String, SourcePreferenceValue> = mapOf(
            "text" to SourcePreferenceValue.Text("中文 📚"),
            "bool" to SourcePreferenceValue.Toggle(true),
            "int" to SourcePreferenceValue.Integer(Int.MIN_VALUE),
            "long" to SourcePreferenceValue.LongInteger(Long.MAX_VALUE),
            "negativeZero" to SourcePreferenceValue.FloatBits((-0f).toRawBits()),
            "infinite" to SourcePreferenceValue.FloatBits(Float.POSITIVE_INFINITY.toRawBits()),
            "nan" to SourcePreferenceValue.FloatBits(0x7fc00001),
            "set" to SourcePreferenceValue.TextSet(setOf("a", "字")),
        )
        val encoded = SourceProtocolJson.encodeToString(values)
        assertTrue(encoded.contains("\"9223372036854775807\""))
        assertEquals(values, SourceProtocolJson.decodeFromString<Map<String, SourcePreferenceValue>>(encoded))
        assertEquals(0x7fc00001, Float.fromBits((values.getValue("nan") as SourcePreferenceValue.FloatBits).bits)
            .toRawBits())
    }
}
