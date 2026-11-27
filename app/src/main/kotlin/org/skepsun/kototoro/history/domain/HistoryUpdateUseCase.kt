package org.skepsun.kototoro.history.domain

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.ui.ReaderState
import javax.inject.Inject

class HistoryUpdateUseCase @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val epubChapterMappingDao: EpubChapterMappingDao,
) {

    suspend operator fun invoke(
        manga: Content,
        readerState: ReaderState,
        percent: Float,
        finishedChapterIds: Set<Long>? = null,
    ) {
        // 从数据库查询父章节ID（用于EPUB内部章节）
        val parentChapterId = extractParentChapterIdFromDatabase(manga.id, readerState.chapterId)

        android.util.Log.d("HistoryUpdateUseCase", "Saving history: chapterId=${readerState.chapterId}, parentChapterId=$parentChapterId, percent=$percent")

        historyRepository.addOrUpdate(
            manga = manga,
            chapterId = readerState.chapterId,
            page = readerState.page,
            scroll = readerState.scroll,
            percent = normalizeCompletedPercent(percent),
            force = false,
            parentChapterId = parentChapterId,
            finishedChapterIds = finishedChapterIds,
        )
    }

    /**
     * Reading the last page can land a hair below 100% (webtoon scroll, float division); store it as a full read
     * so the title is marked completed instead of sitting at 99.x% (issue #750).
     */
    private fun normalizeCompletedPercent(percent: Float): Float =
        if (ReadingProgress.isCompleted(percent)) ReadingProgress.PROGRESS_COMPLETED else percent

    /**
     * 从数据库查询父章节ID（用于EPUB内部章节）
     * 如果是EPUB内部章节，返回父章节ID；否则返回null
     */
    private suspend fun extractParentChapterIdFromDatabase(mangaId: Long, chapterId: Long): Long? {
        return try {
            // 从数据库查询EPUB章节映射
            val mapping = epubChapterMappingDao.findByInternalChapterId(mangaId, chapterId)
            if (mapping != null) {
                android.util.Log.d("HistoryUpdateUseCase", "Found parent chapter from database: chapterId=$chapterId, parentChapterId=${mapping.parentChapterId}")
                mapping.parentChapterId
            } else {
                // 不是EPUB内部章节，或者映射不存在
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("HistoryUpdateUseCase", "Failed to query parent chapter ID", e)
            null
        }
    }

    fun invokeAsync(
        manga: Content,
        readerState: ReaderState,
        percent: Float,
        finishedChapterIds: Set<Long>? = null,
    ) = processLifecycleScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
        runCatchingCancellable {
            withContext(NonCancellable) {
                invoke(manga, readerState, percent, finishedChapterIds)
            }
        }.onFailure {
            it.printStackTraceDebug()
        }
    }
}
