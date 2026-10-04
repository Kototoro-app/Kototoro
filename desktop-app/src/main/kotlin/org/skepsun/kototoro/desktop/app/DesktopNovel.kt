package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.desktop.runtime.NovelBlock

/** What the reader shows for one novel chapter; [firstVisible]/[lastVisible] are block indexes, not pixels. */
data class DesktopNovel(
    val chapter: SourceChapter,
    val blocks: List<NovelBlock>,
    /** Headers for the images the chapter declared, by URL. */
    val images: Map<String, SourceContentImage>,
    val startBlock: Int,
    val firstVisible: Int = startBlock,
    val lastVisible: Int = startBlock,
)

enum class DesktopNovelTheme(val title: String, val background: Long, val text: Long, val muted: Long) {
    LIGHT("白色", 0xFFFFFFFF, 0xFF1B2635, 0xFF687385),
    SEPIA("护眼黄", 0xFFF4ECD8, 0xFF3A2F20, 0xFF7A6A52),
    GREEN("豆沙绿", 0xFFCCE8CF, 0xFF1F3A25, 0xFF4F7357),
    DARK("夜间", 0xFF15191F, 0xFFD5D9E0, 0xFF8E97A6),
}

data class DesktopNovelSettings(
    val fontSize: Int = 19,
    val lineSpacing: Float = 1.75f,
    val width: Int = 760,
    val theme: DesktopNovelTheme = DesktopNovelTheme.LIGHT,
    val serif: Boolean = false,
) {
    companion object {
        val FONT_SIZES = 14..36
        val LINE_SPACINGS = 1.2f..2.6f
        val WIDTHS = 480..1200
    }
}

/** Which reader a content type needs; manga-style sources keep the page reader. */
internal enum class DesktopReaderKind {
    PAGES, NOVEL, VIDEO;

    companion object {
        fun of(content: SourceContent?) = when (content?.source?.contentType) {
            "NOVEL", "HENTAI_NOVEL" -> NOVEL
            "VIDEO", "HENTAI_VIDEO" -> VIDEO
            else -> PAGES
        }
    }
}

/** Debounced reading-position writes, owned by the controller's scope and gate like the continuous page reader. */
internal class DesktopNovelOperations(
    private val state: MutableStateFlow<DesktopAppState>,
    private val session: DesktopSession,
    private val scope: CoroutineScope,
    private val gate: Mutex,
) {
    private val lock = Any()
    private var progressJob: Job? = null
    private var lastSaved: Position? = null

    fun report(chapterId: Long, first: Int, last: Int) {
        val accepted = state.updateAndCheck { snapshot ->
            val novel = snapshot.novel
            if (snapshot.screen != DesktopScreen.NOVEL || novel == null || novel.chapter.id != chapterId ||
                first !in novel.blocks.indices || last !in first until novel.blocks.size) null
            else snapshot.copy(novel = novel.copy(firstVisible = first, lastVisible = last))
        }
        if (!accepted) return
        synchronized(lock) {
            progressJob?.cancel()
            progressJob = scope.launch { delay(400); gate.withLock { flush() } }
        }
    }

    /** Caller owns the gate. Flushed before navigation and exit so the debounce cannot lose the last position. */
    suspend fun flush() {
        val snapshot = state.value
        val novel = snapshot.novel ?: return
        val content = snapshot.content ?: return
        if (snapshot.screen != DesktopScreen.NOVEL) return
        val position = Position(content.id, novel.chapter.id, novel.firstVisible, novel.lastVisible)
        if (position == lastSaved) return
        session.library.recordPage(content, novel.chapter, position.first, novel.blocks.size, position.last)
        lastSaved = position
    }

    suspend fun cancelAndJoin() {
        val job = synchronized(lock) { progressJob.also { progressJob = null } }
        job?.cancel()
        job?.join()
    }

    fun forget() { lastSaved = null }

    /** Applies [transform] atomically; false when it declined (null), so stale reports never schedule a write. */
    private fun MutableStateFlow<DesktopAppState>.updateAndCheck(transform: (DesktopAppState) -> DesktopAppState?): Boolean {
        while (true) {
            val current = value
            val next = transform(current) ?: return false
            if (compareAndSet(current, next)) return true
        }
    }

    private data class Position(val content: Long, val chapter: Long, val first: Int, val last: Int)
}
