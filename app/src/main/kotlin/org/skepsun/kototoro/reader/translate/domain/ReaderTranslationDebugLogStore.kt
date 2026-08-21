package org.skepsun.kototoro.reader.translate.domain

import androidx.collection.LongSparseArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderTranslationDebugLogStore @Inject constructor() {

    private val lock = Any()
    private val pageLogs = LongSparseArray<ArrayDeque<String>>()
    private val updates = MutableSharedFlow<Long>(extraBufferCapacity = 256)

    fun append(pageId: Long, message: String) {
        if (pageId == PAGE_ID_ALL || message.isBlank()) return
        synchronized(lock) {
            val queue = pageLogs[pageId] ?: ArrayDeque<String>().also { pageLogs.put(pageId, it) }
            if (queue.size >= MAX_PAGE_LOG_LINES) {
                repeat(queue.size - MAX_PAGE_LOG_LINES + 1) { queue.removeFirstOrNull() }
            }
            queue.addLast(message)
        }
        updates.tryEmit(pageId)
    }

    fun metric(pageId: Long, key: String, value: Any) {
        append(pageId, "metric.$key=$value")
    }

    fun get(pageId: Long): String {
        synchronized(lock) {
            return pageLogs[pageId]?.joinToString(separator = "\n").orEmpty()
        }
    }

    fun clearPage(pageId: Long) {
        synchronized(lock) {
            pageLogs.remove(pageId)
        }
        if (pageId != PAGE_ID_ALL) {
            updates.tryEmit(pageId)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            pageLogs.clear()
        }
        updates.tryEmit(PAGE_ID_ALL)
    }

    fun observeUpdates(): Flow<Long> = updates.asSharedFlow()

    private companion object {
        const val MAX_PAGE_LOG_LINES = 160
        const val PAGE_ID_ALL = Long.MIN_VALUE
    }
}
