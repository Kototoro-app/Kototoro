package org.skepsun.kototoro.core.replace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/** Codec for both Kototoro's historical rule shape and Legado replace-rule JSON. */
object ReplaceRuleJsonCodec {

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

    fun decode(raw: String): List<ReplaceRule> {
        val element = inputJson.decodeFromString<kotlinx.serialization.json.JsonElement>(raw.trim())
        val objects = when (element) {
            is JsonObject -> listOf(element)
            else -> element.jsonArray.map { it.jsonObject }
        }
        return objects.map(::decodeObject)
    }

    fun encode(rules: List<ReplaceRule>): String = outputJson.encodeToString(
        ListSerializerHolder.serializer,
        rules,
    )

    private fun decodeObject(json: JsonObject): ReplaceRule {
        val canonical = runCatching {
            inputJson.decodeFromJsonElement<ReplaceRule>(json)
        }.getOrNull()
        val pattern = json.string("pattern") ?: json.string("regex") ?: canonical?.pattern.orEmpty()
        val useTo = json.string("useTo")
        val inferredScope = inferScope(useTo)

        return (canonical ?: ReplaceRule()).copy(
            id = json.long("id") ?: canonical?.id ?: 0L,
            name = json.string("name") ?: json.string("replaceSummary") ?: canonical?.name.orEmpty(),
            group = json.string("group") ?: canonical?.group,
            pattern = pattern,
            replacement = json.string("replacement") ?: canonical?.replacement.orEmpty(),
            scopeFilter = json.string("scope") ?: inferredScope.filter,
            scopeTitle = json.bool("scopeTitle") ?: inferredScope.title,
            scopeContent = json.bool("scopeContent") ?: inferredScope.content,
            excludeScope = json.string("excludeScope") ?: canonical?.excludeScope,
            isEnabled = json.bool("isEnabled") ?: json.bool("enable") ?: canonical?.isEnabled ?: true,
            isRegex = json.bool("isRegex") ?: canonical?.isRegex ?: true,
            timeoutMillisecond = json.long("timeoutMillisecond") ?: canonical?.timeoutMillisecond ?: 3000L,
            order = json.int("order") ?: json.int("serialNumber") ?: canonical?.order ?: 0,
        )
    }

    private fun inferScope(useTo: String?): InferredScope {
        return when (useTo?.trim()?.lowercase()) {
            "title", "标题" -> InferredScope(title = true, content = false)
            "all", "both", "titlecontent", "标题正文" -> InferredScope(title = true, content = true)
            "content", "正文" -> InferredScope(title = false, content = true)
            else -> InferredScope(title = false, content = true)
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private data class InferredScope(
        val filter: String? = null,
        val title: Boolean,
        val content: Boolean,
    )

    private object ListSerializerHolder {
        val serializer = kotlinx.serialization.builtins.ListSerializer(ReplaceRule.serializer())
    }
}
