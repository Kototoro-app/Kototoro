package org.skepsun.kototoro.reader.novel

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Base64
import java.security.MessageDigest
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NovelTranslationCorrections @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("novel_translation_corrections", Context.MODE_PRIVATE)

    fun apply(scope: String, translation: NovelChapterTranslation): NovelChapterTranslation {
        val record = preferences.getString(scope, null)?.let(::JSONObject) ?: return translation
        val keys = keys(translation.paragraphs)
        val corrections = keys.mapNotNull { (index, key) ->
            if (record.has(key)) index to record.getString(key) else null
        }.toMap()
        return translation.copy(translations = translation.translations + corrections)
    }

    @Synchronized
    fun set(scope: String, paragraphs: List<NovelParagraph>, index: Int, value: String?) {
        val key = keys(paragraphs)[index] ?: return
        val record = preferences.getString(scope, null)?.let(::JSONObject) ?: JSONObject()
        if (value == null) record.remove(key) else record.put(key, value)
        preferences.edit {
            if (record.length() == 0) remove(scope) else putString(scope, record.toString())
        }
    }

    private fun keys(paragraphs: List<NovelParagraph>): Map<Int, String> {
        val occurrences = HashMap<String, Int>()
        return buildMap {
            for (paragraph in paragraphs) {
                if (paragraph.type != NovelParagraphType.TEXT || paragraph.originalText.isBlank()) continue
                val original = paragraph.originalText
                val occurrence = occurrences.getOrDefault(original, 0)
                occurrences[original] = occurrence + 1
                val bytes = "$occurrence:$original".encodeToByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                put(paragraph.index, Base64.encodeToString(digest, Base64.NO_WRAP))
            }
        }
    }
}
