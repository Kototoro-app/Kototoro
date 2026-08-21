package org.skepsun.kototoro.details.data

import android.content.Context
import androidx.collection.LruCache
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.skepsun.kototoro.parsers.model.Content

@Singleton
class DetailsTranslationCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = LruCache<String, CachedTranslationEntry>(MAX_MEMORY_ENTRIES)

    fun get(
        content: Content,
        sourceLang: String,
        targetLang: String,
        originalTitle: String,
        originalDescription: String,
    ): CachedTranslationEntry? {
        val key = buildKey(content, sourceLang, targetLang)
        val cached = memoryCache[key] ?: prefs.getString(key, null)?.let(::decodeEntry)?.also {
            memoryCache.put(key, it)
        } ?: return null
        return cached.takeIf {
            it.originalTitle == originalTitle && it.originalDescription == originalDescription
        }
    }

    fun put(
        content: Content,
        sourceLang: String,
        targetLang: String,
        entry: CachedTranslationEntry,
    ) {
        val key = buildKey(content, sourceLang, targetLang)
        memoryCache.put(key, entry)
        prefs.edit {
            putString(key, encodeEntry(entry))
        }
    }

    private fun buildKey(content: Content, sourceLang: String, targetLang: String): String {
        val raw = buildString {
            append(content.source.name)
            append('|')
            append(content.url)
            append('|')
            append(sourceLang.trim().lowercase())
            append('|')
            append(targetLang.trim().lowercase())
        }
        return KEY_PREFIX + raw.sha256()
    }

    private fun encodeEntry(entry: CachedTranslationEntry): String {
        return JSONObject()
            .put(KEY_ORIGINAL_TITLE, entry.originalTitle)
            .put(KEY_TRANSLATED_TITLE, entry.translatedTitle)
            .put(KEY_ORIGINAL_DESCRIPTION, entry.originalDescription)
            .put(KEY_TRANSLATED_DESCRIPTION, entry.translatedDescription)
            .put(KEY_SHOWING_TRANSLATION, entry.isShowingTranslation)
            .toString()
    }

    private fun decodeEntry(raw: String): CachedTranslationEntry? {
        return runCatching {
            val json = JSONObject(raw)
            CachedTranslationEntry(
                originalTitle = json.optString(KEY_ORIGINAL_TITLE, ""),
                translatedTitle = json.optString(KEY_TRANSLATED_TITLE).ifBlank { null },
                originalDescription = json.optString(KEY_ORIGINAL_DESCRIPTION, ""),
                translatedDescription = json.optString(KEY_TRANSLATED_DESCRIPTION).ifBlank { null },
                isShowingTranslation = json.optBoolean(KEY_SHOWING_TRANSLATION, true),
            )
        }.getOrNull()
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return buildString(bytes.size * 2) {
            bytes.forEach { append("%02x".format(it)) }
        }
    }

    private companion object {

        const val PREFS_NAME = "details_translation_cache"
        const val KEY_PREFIX = "details_translation_"
        const val KEY_ORIGINAL_TITLE = "original_title"
        const val KEY_TRANSLATED_TITLE = "translated_title"
        const val KEY_ORIGINAL_DESCRIPTION = "original_description"
        const val KEY_TRANSLATED_DESCRIPTION = "translated_description"
        const val KEY_SHOWING_TRANSLATION = "showing_translation"
        const val MAX_MEMORY_ENTRIES = 128
    }
}

data class CachedTranslationEntry(
    val originalTitle: String,
    val translatedTitle: String?,
    val originalDescription: String,
    val translatedDescription: String?,
    val isShowingTranslation: Boolean,
)
