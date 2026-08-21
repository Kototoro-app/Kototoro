package org.skepsun.kototoro.local.epub

import android.util.Log
import java.io.File
import java.util.LinkedHashMap

/**
 * LRU cache for parsed EPUB content.
 *
 * This cache stores parsed EpubContent objects to avoid re-parsing the same EPUB files.
 * Uses a LinkedHashMap with access-order to implement LRU eviction policy.
 *
 * Requirements: 11.2 - Add LRU cache for parsed chapter content (max 10 chapters)
 */
class EpubContentCache private constructor(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {

    // LinkedHashMap with access-order (true) for LRU behavior
    private val cache = object : LinkedHashMap<String, CacheEntry>(
        maxSize + 1,
        0.75f,
        true // access-order
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            val shouldRemove = size > maxSize
            if (shouldRemove && eldest != null) {
                Log.d(TAG, "Evicting from cache: ${eldest.key}")
            }
            return shouldRemove
        }
    }

    /**
     * Gets cached EPUB content.
     *
     * @param file The EPUB file
     * @return Cached content if available and file hasn't been modified, null otherwise
     */
    @Synchronized
    fun get(file: File): EpubContent? {
        val key = file.absolutePath
        val entry = cache[key]

        if (entry == null) {
            Log.d(TAG, "Cache miss: $key")
            return null
        }

        // Check if file has been modified since caching
        if (file.lastModified() != entry.lastModified) {
            Log.d(TAG, "Cache invalidated (file modified): $key")
            cache.remove(key)
            return null
        }

        Log.d(TAG, "Cache hit: $key")
        return entry.content
    }

    /**
     * Puts EPUB content into cache.
     *
     * @param file The EPUB file
     * @param content The parsed content
     */
    @Synchronized
    fun put(file: File, content: EpubContent) {
        val key = file.absolutePath
        cache[key] = CacheEntry(
            content = content,
            lastModified = file.lastModified()
        )
        Log.d(TAG, "Cached: $key (cache size: ${cache.size}/$maxSize)")
    }

    /**
     * Clears all cached content.
     */
    @Synchronized
    fun clear() {
        val size = cache.size
        cache.clear()
        Log.d(TAG, "Cache cleared ($size entries removed)")
    }

    /**
     * Removes a specific entry from cache.
     *
     * @param file The EPUB file to remove
     */
    @Synchronized
    fun remove(file: File) {
        val key = file.absolutePath
        cache.remove(key)
        Log.d(TAG, "Removed from cache: $key")
    }

    /**
     * Gets current cache size.
     */
    @Synchronized
    fun size(): Int = cache.size

    /**
     * Cache entry with file modification timestamp.
     */
    private data class CacheEntry(
        val content: EpubContent,
        val lastModified: Long
    )

    companion object {
        private const val TAG = "EpubContentCache"
        private const val DEFAULT_MAX_SIZE = 10 // Max 10 EPUBs cached (Requirement 11.2)

        @Volatile
        private var instance: EpubContentCache? = null

        fun getInstance(): EpubContentCache {
            return instance ?: synchronized(this) {
                instance ?: EpubContentCache().also { instance = it }
            }
        }
    }
}
