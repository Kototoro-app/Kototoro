package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.download.DownloadPolicy
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.DesktopDownloadSettings
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal enum class DesktopDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED }

/** Waiting or running: the two states a pause can interrupt, and the two a batch pause targets. */
internal val DesktopDownloadStatus.isActive: Boolean
    get() = this == DesktopDownloadStatus.QUEUED || this == DesktopDownloadStatus.DOWNLOADING

internal data class DesktopDownloadState(
    val record: SourceChapterDownload,
    val status: DesktopDownloadStatus,
    val error: String? = null,
) {
    val key: String get() = DesktopDownloadStore.key(record.contentId, record.chapter)
}

/** What the downloaded library costs: whole chapters and pages, plus the image bytes their artifacts hold. */
internal data class DesktopDownloadUsage(
    val chapters: Int,
    val completeChapters: Int,
    val pages: Int,
    val bytes: Long,
)

internal fun desktopDownloadUsage(records: List<SourceChapterDownload>) = DesktopDownloadUsage(
    chapters = records.size,
    completeChapters = records.count { it.isComplete },
    pages = records.sumOf { it.completedPages },
    bytes = records.sumOf { record -> record.pages.sumOf { it.artifact?.byteSize ?: 0L } },
)

/**
 * Which rows a batch action applies to. Kept apart from the queue so the rule is testable without coroutines: a
 * running chapter cannot be resumed, a finished one is not retried, and a failed one is not paused.
 */
internal object DesktopDownloadBatch {
    fun pausable(states: List<DesktopDownloadState>) = states.filter { it.status.isActive }

    fun resumable(states: List<DesktopDownloadState>) =
        states.filter { it.status == DesktopDownloadStatus.PAUSED }

    fun retryable(states: List<DesktopDownloadState>) = states.filter { it.status == DesktopDownloadStatus.FAILED }

    fun removable(states: List<DesktopDownloadState>, selection: Set<String>) =
        states.filter { it.key in selection && !it.status.isActive }
}

/** Selection helpers for the batch bar; keys are chapter identities, so a row keeps its state across refreshes. */
internal object DesktopDownloadSelection {
    fun toggle(selection: Set<String>, key: String): Set<String> =
        if (key in selection) selection - key else selection + key

    fun all(keys: Collection<String>): Set<String> = keys.toSet()

    /** Drops keys that no longer exist, so a removed row cannot be acted on by a later batch. */
    fun retain(selection: Set<String>, keys: Collection<String>): Set<String> = selection intersect keys.toSet()
}

/**
 * The chapter queue, owned by the Controller's scope; restart requires explicit continuation.
 *
 * Its shape follows Android's worker settings: at most `maxActiveSeries` chapters run at once, one chapter fetches up
 * to `threads` pages at a time, requests to the same series are spaced by `requestDelayMs`, and a failed page is
 * retried `retryCount` times `retryDelayMs` apart. The settings are read per chapter and per page, so a change on the
 * downloads settings page applies to the next request instead of requiring a restart.
 */
internal class DesktopDownloads(
    private val store: DesktopDownloadStore,
    private val scope: CoroutineScope,
    private val revision: (SourceChapter) -> String,
    private val pages: suspend (SourceChapter) -> List<SourcePage>,
    private val prepare: suspend (SourcePage, String, SourceImageArtifact?) -> DesktopReaderFile,
    private val settings: () -> DesktopDownloadSettings = { DesktopDownloadSettings() },
) {
    private val mutableState = MutableStateFlow(store.records.value.map {
        DesktopDownloadState(it, if (it.isComplete) DesktopDownloadStatus.COMPLETE else DesktopDownloadStatus.PAUSED)
    })
    val state = mutableState.asStateFlow()
    private val slots = DesktopDownloadSlots { settings().activeSeriesLimit }
    private val pacer = DesktopRequestPacer(delayMs = { DownloadPolicy.sourceDelayMs(settings().requestDelayMs) })
    private val jobs = mutableMapOf<String, Job>()

    @Synchronized
    fun enqueue(contentId: Long, chapter: SourceChapter, contentTitle: String = ""): Job {
        val key = DesktopDownloadStore.key(contentId, chapter)
        jobs[key]?.let { return it }
        val previous = store.find(contentId, chapter)
        val initial = previous ?: SourceChapterDownload(contentId, chapter, revision(chapter), contentTitle = contentTitle)
        store.save(initial)
        publish(DesktopDownloadState(initial, DesktopDownloadStatus.QUEUED))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            slots.withSlot {
                var record = initial
                try {
                    val currentRevision = revision(chapter)
                    if (record.extensionRevision != currentRevision) {
                        record = SourceChapterDownload(contentId, chapter, currentRevision, contentTitle = record.contentTitle)
                    }
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.DOWNLOADING))
                    store.save(record)
                    if (record.pages.isEmpty()) {
                        pacer.await(contentId)
                        val requested = pages(chapter)
                        require(requested.isNotEmpty()) { desktopText("error.noPages") }
                        record = record.copy(pages = requested.map(::SourceDownloadPage))
                    }
                    store.save(record)
                    record = fetchPages(contentId, record, slots = settings())
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.COMPLETE))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    // The manifest saved by fetchPages holds the pages that landed; the local copy predates them.
                    val landed = store.find(contentId, chapter) ?: record
                    publish(DesktopDownloadState(landed, DesktopDownloadStatus.FAILED,
                        error.message ?: desktopText("downloads.failed")))
                } catch (error: LinkageError) {
                    val landed = store.find(contentId, chapter) ?: record
                    publish(DesktopDownloadState(landed, DesktopDownloadStatus.FAILED,
                        desktopText("error.sourceApiIncompatibleShort")))
                }
            }
        }
        jobs[key] = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (jobs[key] === job) {
                    jobs.remove(key)
                    mutableState.update { list -> list.map {
                        if (it.key == key && it.status in setOf(DesktopDownloadStatus.QUEUED, DesktopDownloadStatus.DOWNLOADING))
                            it.copy(status = DesktopDownloadStatus.PAUSED) else it
                    } }
                }
            }
        }
        job.start()
        return job
    }

    @Synchronized
    fun pause(key: String) { jobs[key]?.cancel() }

    /** Everything still waiting or running stops at the next page boundary; finished chapters are untouched. */
    @Synchronized
    fun pauseAll() { jobs.values.toList().forEach { it.cancel() } }

    /** The usage figure the page shows; it comes from the saved records, so it survives a restart. */
    fun usage(): DesktopDownloadUsage = desktopDownloadUsage(store.records.value)

    /** Waits for a cancelled chapter to settle, so its manifest cannot be rewritten after the caller deletes it. */
    suspend fun settle(key: String) {
        val running = synchronized(this) { jobs[key] }
        running?.join()
    }

    /** Drops rows whose records the caller already deleted from the store. */
    @Synchronized
    fun forget(keys: Collection<String>) {
        if (keys.isEmpty()) return
        mutableState.update { list -> list.filterNot { it.key in keys } }
    }

    /**
     * Fetches the chapter's pages with the configured page width: workers pull indices from one counter, so a slow page
     * does not hold the ones behind it, and every successful page is written into the manifest before the next one
     * starts. A failure cancels the siblings, saves what already landed and rethrows, which is what makes a resumed
     * chapter refetch only the pages the reader cache cannot verify.
     */
    private suspend fun fetchPages(
        contentId: Long,
        source: SourceChapterDownload,
        slots: DesktopDownloadSettings,
    ): SourceChapterDownload {
        var record = source
        val items = record.pages.toMutableList()
        val mutex = Mutex()
        var failure: Throwable? = null
        try {
            coroutineScope {
                val counter = AtomicInteger(0)
                val workers = minOf(slots.threads, items.size).coerceAtLeast(1)
                repeat(workers) {
                    launch {
                        while (true) {
                            val index = counter.getAndIncrement()
                            if (index >= items.size) break
                            currentCoroutineContext().ensureActive()
                            val item = items[index]
                            pacer.await(contentId)
                            val file = withRetries(slots) {
                                prepare(item.page, record.extensionRevision, item.artifact)
                            }
                            currentCoroutineContext().ensureActive()
                            mutex.withLock {
                                items[index] = SourceDownloadPage(
                                    item.page,
                                    file.artifact,
                                    file.image.width,
                                    file.image.height,
                                )
                                record = record.copy(pages = items.toList())
                                store.save(record)
                                publish(DesktopDownloadState(record, DesktopDownloadStatus.DOWNLOADING))
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
        }
        // Saves the pages that landed even when the chapter failed or was paused; no worker is running here.
        record = record.copy(pages = items.toList())
        store.save(record)
        publish(DesktopDownloadState(record, DesktopDownloadStatus.DOWNLOADING))
        failure?.let { throw it }
        return record
    }

    /**
     * Android's `runFailsafe` without the interactive pause: a page that failed for a reason a second attempt can cure
     * is retried, a cancelled page is not. The source layer wraps every exception it catches into
     * [SourceRemoteException] with [SourceErrorCode.RUNTIME_FAILURE], so that code has to count as retryable here —
     * otherwise a 503 on one page would abandon the whole chapter and the retry row would do nothing.
     */
    private suspend fun <T> withRetries(settings: DesktopDownloadSettings, block: suspend () -> T): T {
        val attempts = DownloadPolicy.attempts(settings.retryCount)
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isRetryable(error)) throw error
                if (attempt >= attempts) throw error
                attempt += 1
                delay(DownloadPolicy.retryDelayMs(settings.retryDelayMs, -1L))
            }
        }
    }

    /** A network error or a source that failed at runtime; a challenge, a bad argument or an unsupported call is not. */
    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is SourceRemoteException -> error.error.code == SourceErrorCode.RUNTIME_FAILURE
        else -> false
    }

    private fun publish(value: DesktopDownloadState) {
        mutableState.update { list -> list.filter { it.key != value.key } + value }
    }
}
