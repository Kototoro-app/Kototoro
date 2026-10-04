package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.desktop.player.MpvExternalTrack
import org.skepsun.kototoro.desktop.player.MpvStream
import kotlin.math.ceil

/**
 * One episode open in the player: the streams the source offered (qualities / mirrors), which one plays, and where
 * playback is. [generation] changes whenever the player must (re)load, even for the same stream.
 */
data class DesktopVideo(
    val chapter: SourceChapter,
    val streams: List<SourcePage>,
    val selected: Int = 0,
    val startSeconds: Double = 0.0,
    val position: Double = startSeconds,
    val duration: Double = 0.0,
    val generation: Long = 0,
) {
    val stream: SourcePage get() = streams[selected.coerceIn(streams.indices)]

    fun label(index: Int): String {
        val page = streams[index]
        val quality = page.playbackQuality?.let { "${it}p" }
        return listOfNotNull(page.playbackLabel?.takeIf(String::isNotBlank), quality)
            .distinct().joinToString(" · ").ifBlank { "线路 ${index + 1}" }
    }
}

/** What libmpv needs to play [this]: its headers also apply to the subtitle and audio tracks fetched next to it. */
internal fun SourcePage.toMpvStream(startSeconds: Double, title: String?) = MpvStream(
    url = url,
    headers = headers.orEmpty(),
    subtitles = externalSubtitleTracks.map { MpvExternalTrack(it.url, it.lang) },
    audio = externalAudioTracks.map { MpvExternalTrack(it.url, it.lang) },
    startSeconds = startSeconds,
    title = title,
)

/**
 * Debounced watch-position writes. History keeps a page/page-count pair, so a video is recorded in whole seconds:
 * page = the second reached, page count = the length, which also gives the library its percentage.
 */
internal class DesktopVideoOperations(
    private val state: MutableStateFlow<DesktopAppState>,
    private val session: DesktopSession,
    private val scope: CoroutineScope,
    private val gate: Mutex,
) {
    private val lock = Any()
    private var progressJob: Job? = null
    private var lastSaved: Position? = null

    fun report(chapterId: Long, position: Double, duration: Double) {
        if (!position.isFinite() || !duration.isFinite() || duration <= 0) return
        var accepted = false
        while (true) {
            val current = state.value
            val video = current.video
            if (current.screen != DesktopScreen.VIDEO || video == null || video.chapter.id != chapterId) break
            if (state.compareAndSet(current, current.copy(video = video.copy(position = position, duration = duration)))) {
                accepted = true
                break
            }
        }
        if (!accepted) return
        synchronized(lock) {
            if (progressJob?.isActive == true) return
            progressJob = scope.launch { delay(5_000); gate.withLock { flush() } }
        }
    }

    /** Caller owns the gate. */
    suspend fun flush() {
        val snapshot = state.value
        val video = snapshot.video ?: return
        val content = snapshot.content ?: return
        if (snapshot.screen != DesktopScreen.VIDEO || video.duration <= 0) return
        val length = ceil(video.duration).toInt().coerceAtLeast(1)
        val second = video.position.toInt().coerceIn(0, length - 1)
        val position = Position(content.id, video.chapter.id, second)
        if (position == lastSaved) return
        session.library.recordPage(content, video.chapter, second, length, second)
        lastSaved = position
    }

    suspend fun cancelAndJoin() {
        val job = synchronized(lock) { progressJob.also { progressJob = null } }
        job?.cancel()
        job?.join()
    }

    fun forget() { lastSaved = null }

    private data class Position(val content: Long, val chapter: Long, val second: Int)
}
