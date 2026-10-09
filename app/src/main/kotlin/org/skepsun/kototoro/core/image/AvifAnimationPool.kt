package org.skepsun.kototoro.core.image

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Keeps paused AVIF decoders and their bounded frame queues after their consumer releases them.
 *
 * The frames are owned by one consumer at a time ([AvifAnimatedDrawable] is not shareable, which is
 * also why Coil's memory cache cannot keep it). Without this, every reader that drops an off-screen
 * page — paged, webtoon and scene alike — had to open the decoder again on return. A released drawable
 * is parked here, stopped; the next decode of the same file at the same requested size takes it back.
 * [budgetBytes] bounds encoded input plus RGB buffers, oldest first. Codec working memory is additional.
 */
class AvifAnimationPool(
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val budgetProvider: (() -> Long)? = null,
) : ComponentCallbacks2 {

    /** Everything the decoded frames depend on: the file's identity and the decode request. */
    data class Key(
        val path: String,
        val length: Long?,
        val lastModifiedMillis: Long?,
        val request: String,
    )

    private class Entry(val drawable: AvifAnimatedDrawable, val isSampled: Boolean)

    private val parked = LinkedHashMap<Key, Entry>(8, 0.75f, true)
    private var parkedBytes = 0L

    class Taken(val drawable: AvifAnimatedDrawable, val isSampled: Boolean)

    @Synchronized
    fun take(key: Key): Taken? {
        trimToBudget(currentBudget())
        val entry = parked.remove(key) ?: return null
        parkedBytes -= entry.drawable.retainedByteCount
        entry.drawable.revive()
        if (!entry.drawable.isUsable()) {
            entry.drawable.recycleFrames()
            return null
        }
        return Taken(entry.drawable, entry.isSampled)
    }

    /** Returns false when [drawable] is not kept, in which case the caller frees it. */
    @Synchronized
    fun park(key: Key, drawable: AvifAnimatedDrawable, isSampled: Boolean): Boolean {
        val limit = currentBudget()
        trimToBudget(limit)
        if (drawable.retainedByteCount > limit) return false
        parked.remove(key)?.let { previous ->
            parkedBytes -= previous.drawable.retainedByteCount
            if (previous.drawable !== drawable) previous.drawable.recycleFrames()
        }
        parked[key] = Entry(drawable, isSampled)
        parkedBytes += drawable.retainedByteCount
        trimToBudget(limit)
        return true
    }

    private fun currentBudget() = (budgetProvider?.invoke() ?: budgetBytes).coerceAtLeast(0)

    private fun trimToBudget(limit: Long) {
        val iterator = parked.values.iterator()
        while (parkedBytes > limit && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            parkedBytes -= eldest.drawable.retainedByteCount
            eldest.drawable.recycleFrames()
        }
    }

    @Synchronized
    fun clear() {
        parked.values.forEach { it.drawable.recycleFrames() }
        parked.clear()
        parkedBytes = 0L
    }

    /**
     * Parked frames are only a shortcut, so they are the first memory to give up. Leaving the UI
     * briefly (switching apps) keeps them; pressure on the running app, or the app becoming a kill
     * candidate in the background, frees them.
     */
    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val underPressure = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (underPressure) clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = clear()

    companion object {
        /** Standalone pool default; the app supplies a device-dependent budget. */
        const val DEFAULT_BUDGET_BYTES = 128L * 1024 * 1024
    }
}
