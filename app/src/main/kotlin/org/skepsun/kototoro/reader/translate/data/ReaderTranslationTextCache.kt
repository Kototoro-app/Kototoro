package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import androidx.collection.LruCache
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderTranslationTextCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = LruCache<String, String>(MAX_MEMORY_ENTRIES)

    operator fun get(key: String): String? {
        val memory = memoryCache[key]
        if (memory != null) {
            return memory
        }
        val disk = prefs.getString(key, null) ?: return null
        memoryCache.put(key, disk)
        return disk
    }

    operator fun set(key: String, value: String) {
        memoryCache.put(key, value)
        prefs.edit { putString(key, value) }
    }

    fun clear() {
        memoryCache.evictAll()
        prefs.edit { clear() }
    }

    private companion object {

        const val PREFS_NAME = "reader_translation_text_cache"
        const val MAX_MEMORY_ENTRIES = 2048
    }
}
