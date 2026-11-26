package org.skepsun.kototoro.community.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Where a chart work's cover is: the source that has it and the address, enough to load it without the work. */
data class CommunityCover(val sourceName: String, val url: String)

/** What the cache knows about a work: its cover, or that no source of the reader's had one when last looked. */
sealed interface CachedCover {
    data class Found(val cover: CommunityCover) : CachedCover
    data object Missing : CachedCover
}

/**
 * Covers found for community chart works, kept on the device. The chart names works by title only, so a cover costs a
 * search of the reader's sources; without this every visit to the page paid it again. A found cover is kept for a month
 * and a work no source had for two days, after which it is looked for again (a source may have been added since).
 */
@Singleton
class CommunityCoverCache @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cached answer for work [id], or null when it was never looked up or the answer has expired. */
    @Synchronized
    fun get(id: String, now: Long = System.currentTimeMillis()): CachedCover? {
        val raw = prefs.getString(id, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val age = now - json.optLong(AT)
        val source = json.optString(SOURCE)
        val url = json.optString(URL)
        return if (source.isNotEmpty() && url.isNotEmpty()) {
            CachedCover.Found(CommunityCover(source, url)).takeIf { age in 0..FOUND_FOR_MS }
        } else {
            CachedCover.Missing.takeIf { age in 0..MISSING_FOR_MS }
        }
    }

    @Synchronized
    fun putFound(id: String, cover: CommunityCover, now: Long = System.currentTimeMillis()) =
        put(id, JSONObject().put(AT, now).put(SOURCE, cover.sourceName).put(URL, cover.url))

    @Synchronized
    fun putMissing(id: String, now: Long = System.currentTimeMillis()) = put(id, JSONObject().put(AT, now))

    private fun put(id: String, json: JSONObject) {
        prefs.edit { putString(id, json.toString()) }
        if (prefs.all.size > MAX_ENTRIES) trim()
    }

    /** Drops the oldest answers so the file does not grow with every chart the community ever had. */
    private fun trim() {
        val oldest = prefs.all.mapNotNull { (key, value) ->
            val at = (value as? String)?.let { runCatching { JSONObject(it).optLong(AT) }.getOrNull() } ?: 0L
            key to at
        }.sortedBy { it.second }.take(prefs.all.size - MAX_ENTRIES + TRIM_EXTRA)
        prefs.edit { oldest.forEach { remove(it.first) } }
    }

    private companion object {
        const val PREFS = "community_covers"
        const val AT = "at"
        const val SOURCE = "source"
        const val URL = "url"
        const val MAX_ENTRIES = 300
        const val TRIM_EXTRA = 50
        const val FOUND_FOR_MS = 30L * 24 * 60 * 60 * 1000
        const val MISSING_FOR_MS = 2L * 24 * 60 * 60 * 1000
    }
}
