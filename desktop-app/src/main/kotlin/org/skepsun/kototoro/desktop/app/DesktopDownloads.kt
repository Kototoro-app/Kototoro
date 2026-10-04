package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.*

internal enum class DesktopDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED }
internal data class DesktopDownloadState(
    val record: SourceChapterDownload,
    val status: DesktopDownloadStatus,
    val error: String? = null,
) {
    val key: String get() = DesktopDownloadStore.key(record.contentId, record.chapter)
}

/** One serial chapter queue, owned by the Controller's scope; restart requires explicit continuation. */
internal class DesktopDownloads(
    private val store: DesktopDownloadStore,
    private val scope: CoroutineScope,
    private val revision: (SourceChapter) -> String,
    private val pages: suspend (SourceChapter) -> List<SourcePage>,
    private val prepare: suspend (SourcePage, String, SourceImageArtifact?) -> DesktopReaderFile,
) {
    private val mutableState = MutableStateFlow(store.records.value.map {
        DesktopDownloadState(it, if (it.isComplete) DesktopDownloadStatus.COMPLETE else DesktopDownloadStatus.PAUSED)
    })
    val state = mutableState.asStateFlow()
    private val gate = Mutex()
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
            gate.withLock {
                var record = initial
                try {
                    val currentRevision = revision(chapter)
                    if (record.extensionRevision != currentRevision) {
                        record = SourceChapterDownload(contentId, chapter, currentRevision, contentTitle = record.contentTitle)
                    }
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.DOWNLOADING))
                    store.save(record)
                    if (record.pages.isEmpty()) {
                        val requested = pages(chapter)
                        require(requested.isNotEmpty()) { "此章节没有页面" }
                        record = record.copy(pages = requested.map(::SourceDownloadPage))
                    }
                    store.save(record)
                    for (index in record.pages.indices) {
                        currentCoroutineContext().ensureActive()
                        val item = record.pages[index]
                        val file = prepare(item.page, record.extensionRevision, item.artifact)
                        currentCoroutineContext().ensureActive()
                        record = record.copy(pages = record.pages.toMutableList().also {
                            it[index] = SourceDownloadPage(item.page, file.artifact, file.image.width, file.image.height)
                        })
                        store.save(record)
                        publish(DesktopDownloadState(record, DesktopDownloadStatus.DOWNLOADING))
                    }
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.COMPLETE))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.FAILED, error.message ?: "下载失败"))
                } catch (error: LinkageError) {
                    publish(DesktopDownloadState(record, DesktopDownloadStatus.FAILED, "来源 API 不兼容"))
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

    private fun publish(value: DesktopDownloadState) {
        mutableState.update { list -> list.filter { it.key != value.key } + value }
    }
}
