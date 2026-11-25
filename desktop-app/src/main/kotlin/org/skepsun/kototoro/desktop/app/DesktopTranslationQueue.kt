package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent

internal class DesktopTranslationQueue(
    private val scope: CoroutineScope,
    private val translation: DesktopNovelTranslation,
    private val load: suspend (SourceContent, SourceChapter) -> List<DesktopTranslationParagraph>,
) {
    data class Key(val bookId: Long, val chapterId: Long)
    enum class Status { QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED }
    data class Item(
        val key: Key,
        val title: String,
        val status: Status = Status.QUEUED,
        val completed: Int = 0,
        val total: Int = 0,
        val error: String? = null,
    )
    private data class Request(val book: SourceContent, val chapter: SourceChapter, val retranslate: Boolean) {
        val key = Key(book.id, chapter.id)
    }

    private val pending = ArrayList<Request>()
    private var active: Pair<Key, Job>? = null
    private val mutableItems = MutableStateFlow<List<Item>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val mutablePaused = MutableStateFlow(false)
    val paused = mutablePaused.asStateFlow()

    @Synchronized
    fun enqueue(book: SourceContent, chapters: List<SourceChapter>, retranslate: Boolean = false) {
        for (chapter in chapters) {
            val request = Request(book, chapter, retranslate)
            if (active?.first == request.key || pending.any { it.key == request.key }) continue
            pending += request
            mutableItems.value = mutableItems.value.filterNot { it.key == request.key } + Item(
                request.key, chapter.title ?: desktopText("common.chapterTitle", chapter.number),
            )
        }
        startNext()
    }

    @Synchronized
    fun move(key: Key, delta: Int) {
        val positions = pending.indices.filter { pending[it].key.bookId == key.bookId }
        val index = positions.indexOfFirst { pending[it].key == key }
        if (index < 0) return
        val destination = positions.getOrNull(index + delta) ?: return
        pending.add(destination, pending.removeAt(positions[index]))
        val old = mutableItems.value.associateBy(Item::key)
        mutableItems.value = pending.map { old.getValue(it.key) } + old.values.filter { it.status != Status.QUEUED }
    }

    @Synchronized
    fun cancel(key: Key) {
        if (pending.removeAll { it.key == key }) {
            update(key) { it.copy(status = Status.CANCELLED) }
        } else {
            active?.takeIf { it.first == key }?.second?.cancel()
        }
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        mutablePaused.value = value
        startNext()
    }

    @Synchronized
    private fun update(key: Key, transform: (Item) -> Item) {
        mutableItems.value = mutableItems.value.map { if (it.key == key) transform(it) else it }
    }

    private fun startNext() {
        if (active != null || mutablePaused.value || pending.isEmpty()) return
        val request = pending.removeAt(0)
        val key = request.key
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val settings = translation.settings.value
                val paragraphs = load(request.book, request.chapter)
                translation.translate(key.bookId, key.chapterId, paragraphs, settings, request.retranslate,
                    beforeBatch = { mutablePaused.first { !it } }) { completed, total ->
                    update(key) { it.copy(completed = completed, total = total) }
                }
                update(key) { it.copy(status = Status.COMPLETE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(key) { it.copy(status = Status.FAILED, error = e.localizedMessage) }
            }
        }
        active = key to job
        update(key) { it.copy(status = Status.RUNNING) }
        job.invokeOnCompletion { failure ->
            synchronized(this) {
                if (failure is CancellationException) update(key) { it.copy(status = Status.CANCELLED) }
                active = null
                if (scope.coroutineContext[Job]?.isActive != false) startNext()
            }
        }
        job.start()
    }
}
