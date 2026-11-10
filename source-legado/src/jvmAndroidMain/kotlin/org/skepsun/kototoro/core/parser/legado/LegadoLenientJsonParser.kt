package org.skepsun.kototoro.core.parser.legado

import com.google.gson.stream.JsonReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/**
 * 解析 Legado 规则里常见的宽松 JSON 片段。
 *
 * 目标是尽量贴近 MD3 里 GSON lenient 的行为：
 * - 支持单引号
 * - 支持未加引号的 key
 * - 支持尾随逗号等宽松写法
 */
object LegadoLenientJsonParser {

    fun parseObject(raw: String): JSONObject {
        return runCatching { JSONObject(raw) }.getOrElse {
            val reader = JsonReader(StringReader(raw)).apply {
                @Suppress("DEPRECATION")
                isLenient = true
            }
            val parsed = readAny(reader)
            when (parsed) {
                is JSONObject -> parsed
                else -> throw IllegalArgumentException("Expected JSON object")
            }
        }
    }

    private fun readAny(reader: JsonReader): Any? {
        return when (reader.peek()) {
            com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                val obj = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    obj.put(reader.nextName(), readAny(reader))
                }
                reader.endObject()
                obj
            }

            com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                val array = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) {
                    array.put(readAny(reader))
                }
                reader.endArray()
                array
            }

            com.google.gson.stream.JsonToken.STRING -> reader.nextString()
            com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean()
            com.google.gson.stream.JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }

            com.google.gson.stream.JsonToken.NUMBER -> {
                val numberText = reader.nextString()
                numberText.toLongOrNull()
                    ?: numberText.toDoubleOrNull()
                    ?: numberText
            }

            else -> throw IllegalArgumentException("Unsupported JSON token: ${reader.peek()}")
        }
    }
}
