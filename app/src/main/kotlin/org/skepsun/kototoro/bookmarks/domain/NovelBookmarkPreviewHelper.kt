package org.skepsun.kototoro.bookmarks.domain

import androidx.collection.LruCache

private val novelBookmarkPreviewCache = LruCache<String, String>(256)

/** Keeps the Android presentation cache while extraction uses the shared preview policy and Jsoup adapter. */
fun extractNovelBookmarkPreview(imageUrl: String?): String {
    if (imageUrl.isNullOrBlank()) return ""
    novelBookmarkPreviewCache.get(imageUrl)?.let { return it }
    return parseNovelBookmarkPreview(imageUrl).also { novelBookmarkPreviewCache.put(imageUrl, it) }
}
