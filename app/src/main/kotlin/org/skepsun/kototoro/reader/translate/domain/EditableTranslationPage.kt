package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

internal data class EditableTranslationPage(
    val localUri: Uri,
    val renderKey: String,
    val sourceLang: String,
    val targetLang: String,
    val inputs: List<BubbleInput>,
    val machineTranslations: Map<String, String>,
    val corrections: Map<Int, String> = emptyMap(),
    val bookKey: String? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("uri", localUri.toString())
        put("key", renderKey)
        put("source", sourceLang)
        put("target", targetLang)
        if (!bookKey.isNullOrBlank()) {
            put("bookKey", bookKey)
        }
        put("translations", JSONObject(machineTranslations))
        put("corrections", JSONObject(corrections.mapKeys { it.key.toString() }))
        put("inputs", JSONArray(inputs.map { input ->
            JSONObject().apply {
                put("bounds", input.rect.flattenToString())
                put("text", input.sourceText)
                put("vertical", input.verticalPreferred)
                put("class", input.classId)
                put("anchored", input.detectorAnchored)
                put("content", input.sourceContentRect?.flattenToString())
                put("regions", JSONArray(input.sourceContentRects.map(Rect::flattenToString)))
            }
        }))
    }.toString()

    companion object {
        fun decode(value: String): EditableTranslationPage {
            val data = JSONObject(value)
            val inputs = data.getJSONArray("inputs")
            val translations = data.getJSONObject("translations")
            val corrections = data.getJSONObject("corrections")
            return EditableTranslationPage(
                localUri = data.getString("uri").toUri(),
                renderKey = data.getString("key"),
                sourceLang = data.getString("source"),
                targetLang = data.getString("target"),
                inputs = List(inputs.length()) { index ->
                    val bubble = inputs.getJSONObject(index)
                    val regions = bubble.getJSONArray("regions")
                    BubbleInput(
                        rect = checkNotNull(Rect.unflattenFromString(bubble.getString("bounds"))),
                        sourceText = bubble.getString("text"),
                        verticalPreferred = bubble.getBoolean("vertical"),
                        classId = bubble.getInt("class"),
                        detectorAnchored = bubble.getBoolean("anchored"),
                        sourceContentRect = Rect.unflattenFromString(bubble.optString("content")),
                        sourceContentRects = (0 until regions.length()).mapNotNull {
                            Rect.unflattenFromString(regions.getString(it))
                        },
                    )
                },
                machineTranslations = translations.keys().asSequence().associateWith(translations::getString),
                corrections = corrections.keys().asSequence().associate { it.toInt() to corrections.getString(it) },
                bookKey = data.optString("bookKey").ifBlank { null },
            )
        }
    }
}
