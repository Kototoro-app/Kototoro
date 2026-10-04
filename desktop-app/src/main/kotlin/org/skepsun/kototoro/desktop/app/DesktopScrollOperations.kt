package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Latest visible demand and bounded-rate history writes, owned by the existing controller scope/gate. */
internal class DesktopScrollOperations(private val state: MutableStateFlow<DesktopAppState>,
    private val session: DesktopSession, private val scope: CoroutineScope, private val gate: Mutex) {
    private val lock = Any()
    private val generation = AtomicLong()
    private var imagesJob: Job? = null
    private var progressJob: Job? = null
    private var wanted: Pair<Long, List<Int>>? = null
    private var lastSaved: Position? = null

    fun load(chapterId: Long, indices: List<Int>, refresh: Boolean = false): Job = synchronized(lock) {
        val positions = indices.distinct().sorted()
        val request = chapterId to positions
        val snapshot = state.value
        if (!refresh && wanted == request && imagesJob != null && (imagesJob!!.isActive ||
            positions.all { position -> snapshot.pages.getOrNull(position)?.id?.let {
                it in snapshot.readerImages || it in snapshot.readerFailedPages
            } == true })) return@synchronized imagesJob!!
        imagesJob?.cancel()
        val epoch = generation.incrementAndGet()
        wanted = request
        scope.launch {
            try {
                gate.withLock {
                    val snapshot = state.value
                    if (!accepts(snapshot, chapterId) || snapshot.busy || positions.any { it !in snapshot.pages.indices }) {
                        return@withLock
                    }
                    val needed = positions.filter { refresh || (snapshot.pages[it].id !in snapshot.readerImages &&
                        snapshot.pages[it].id !in snapshot.readerFailedPages) }
                    state.update { it.copy(readerVisiblePages = positions,
                        readerLoading = needed.map { position -> snapshot.pages[position].id }.toSet()) }
                    val block: suspend () -> Unit = {
                        for (position in needed) {
                            currentCoroutineContext().ensureActive()
                            val page = snapshot.pages[position]
                            try {
                                val image = session.readerImage(page, refresh)
                                if (generation.get() == epoch) state.update {
                                    if (accepts(it, chapterId)) it.copy(readerImages = it.readerImages + (page.id to image),
                                        readerFailedPages = it.readerFailedPages - page.id, readerLoading = it.readerLoading - page.id,
                                        error = null) else it
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { failed(chapterId, epoch, page.id, error.message ?: "页面加载失败") }
                            catch (error: LinkageError) { failed(chapterId, epoch, page.id, "来源 API 不兼容") }
                        }
                    }
                    val challenges = session.browserChallenges
                    if (challenges == null) block() else challenges.withRequestCancellation(block)
                }
            } finally {
                if (generation.get() == epoch) state.update { it.copy(readerLoading = emptySet()) }
            }
        }.also { imagesJob = it }
    }

    private fun failed(chapterId: Long, epoch: Long, pageId: Long, message: String) {
        if (generation.get() == epoch) state.update {
            if (accepts(it, chapterId)) it.copy(readerFailedPages = it.readerFailedPages + (pageId to message),
                readerLoading = it.readerLoading - pageId, error = message) else it
        }
    }

    fun report(chapterId: Long, first: Int, offset: Float, last: Int, atEnd: Boolean) {
        if (!offset.isFinite() || offset < 0f) return
        state.update {
            if (!accepts(it, chapterId) || it.busy || first !in it.pages.indices || last !in first..it.pages.lastIndex ||
                (first..last).any { position -> it.pages[position].id !in it.readerImages ||
                    it.pages[position].id in it.readerFailedPages }) it else {
                it.copy(pageIndex = first, readerScroll = offset, readerLastVisible = last,
                    readerScrollReady = true, readerAtEnd = atEnd)
            }
        }
        synchronized(lock) {
            progressJob?.cancel()
            progressJob = scope.launch { delay(150); gate.withLock { flush() } }
        }
    }

    /** Caller owns the gate. Flush before navigation/exit so the debounce cannot lose the final verified viewport. */
    suspend fun flush() {
        val snapshot = state.value
        val chapter = snapshot.chapter ?: return
        if (!accepts(snapshot, chapter.id) || !snapshot.readerScrollReady) return
        val content = snapshot.content ?: return
        val position = Position(content.id, chapter.id, snapshot.pageIndex, snapshot.readerScroll, snapshot.readerLastVisible)
        if (position == lastSaved) return
        session.library.recordPage(content, chapter, position.first, snapshot.pages.size, position.last, position.offset)
        lastSaved = position
    }

    suspend fun cancelAndJoin() {
        val jobs = synchronized(lock) {
            generation.incrementAndGet()
            wanted = null
            listOfNotNull(imagesJob, progressJob).also {
                imagesJob = null; progressJob = null; it.forEach { job -> job.cancel() }
            }
        }
        jobs.joinAll()
        lastSaved = null
        state.update { it.copy(readerLoading = emptySet()) }
    }

    private fun accepts(snapshot: DesktopAppState, chapterId: Long) = snapshot.screen == DesktopScreen.READER &&
        snapshot.chapter?.id == chapterId && snapshot.readerSettings.mode == DesktopReaderMode.CONTINUOUS

    private data class Position(val content: Long, val chapter: Long, val first: Int, val offset: Float, val last: Int)
}
