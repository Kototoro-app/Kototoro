package org.skepsun.kototoro.download.ui.worker

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueuePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("download_queue_edits", Context.MODE_PRIVATE)
    private val acquired = HashMap<UUID, MutableSet<Long>>()
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    @Synchronized
    fun pending(workId: UUID, chapters: List<Long>): List<Long> {
        val started = acquired[workId].orEmpty()
        return configured(workId, chapters).filterNot { it in started }
    }

    @Synchronized
    fun configured(workId: UUID, chapters: List<Long>): List<Long> {
        val excluded = preferences.getStringSet("$workId:excluded", emptySet()).orEmpty()
        val available = chapters.filterNot { it.toString() in excluded }.toSet()
        return (order(workId) + chapters).distinct().filter { it in available }
    }

    @Synchronized
    fun acquireNext(workId: UUID, chapters: List<Long>): Long? {
        val next = pending(workId, chapters).firstOrNull() ?: return null
        acquired.getOrPut(workId) { HashSet() }.add(next)
        changes.value++
        return next
    }

    @Synchronized
    fun move(workId: UUID, chapters: List<Long>, chapterId: Long, delta: Int) {
        val pending = pending(workId, chapters).toMutableList()
        val from = pending.indexOf(chapterId)
        val to = from + delta
        if (from < 0 || to !in pending.indices) return
        pending.add(to, pending.removeAt(from))
        preferences.edit { putString("$workId:order", pending.joinToString(",")) }
        changes.value++
    }

    @Synchronized
    fun cancel(workId: UUID, chapters: List<Long>, chapterId: Long) {
        if (chapterId !in pending(workId, chapters)) return
        val key = "$workId:excluded"
        preferences.edit { putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() + chapterId.toString()) }
        changes.value++
    }

    @Synchronized
    fun registerActive(workId: UUID, isPaused: Boolean) {
        ActiveDownloadRegistry.register(workId, isPaused, isPrioritized(workId))
    }

    @Synchronized
    fun setPrioritized(workId: UUID, value: Boolean) {
        preferences.edit { if (value) putBoolean("$workId:priority", true) else remove("$workId:priority") }
        ActiveDownloadRegistry.setPrioritized(workId, value)
        changes.value++
    }

    fun isPrioritized(workId: UUID): Boolean = preferences.getBoolean("$workId:priority", false)

    @Synchronized
    fun resetAcquired(workId: UUID) {
        if (acquired.remove(workId) != null) changes.value++
    }

    @Synchronized
    fun remove(workId: UUID) {
        acquired.remove(workId)
        preferences.edit {
            remove("$workId:priority")
            remove("$workId:order")
            remove("$workId:excluded")
        }
        changes.value++
    }

    private fun order(workId: UUID): List<Long> =
        preferences.getString("$workId:order", null)?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()
}
