package org.skepsun.kototoro.core.dictionary

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

object DictionaryRuleJsonCodec {

    private val inputJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        coerceInputValues = true
    }

    private val outputJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun decode(raw: String): List<DictionaryRule> {
        val element = inputJson.decodeFromString<JsonElement>(raw.trim())
        val objects = when (element) {
            is JsonObject -> listOf(element)
            else -> element.jsonArray.map { it.jsonObject }
        }
        return objects.map { json ->
            val canonical = runCatching {
                inputJson.decodeFromJsonElement(DictionaryRule.serializer(), json)
            }.getOrNull()
            DictionaryRule(
                name = json.string("name") ?: canonical?.name.orEmpty(),
                urlRule = json.string("urlRule") ?: json.string("url") ?: canonical?.urlRule.orEmpty(),
                showRule = json.string("showRule") ?: canonical?.showRule.orEmpty(),
                enabled = json.bool("enabled") ?: canonical?.enabled ?: true,
                sortNumber = json.int("sortNumber") ?: json.int("sortOrder") ?: canonical?.sortNumber ?: 0,
            )
        }.filter { it.name.isNotBlank() && it.urlRule.isNotBlank() }
    }

    fun encode(rules: List<DictionaryRule>): String = outputJson.encodeToString(rules)

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull
}
