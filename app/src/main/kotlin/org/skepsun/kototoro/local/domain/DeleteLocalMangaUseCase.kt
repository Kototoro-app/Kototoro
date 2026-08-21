package org.skepsun.kototoro.local.domain

import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.IOException
import javax.inject.Inject

class DeleteLocalContentUseCase @Inject constructor(
    private val localContentRepository: LocalMangaRepository,
    private val historyRepository: HistoryRepository,
) {

    suspend operator fun invoke(manga: Content) {
        val victim = if (manga.isLocal) manga else localContentRepository.findSavedContent(manga)?.manga
        checkNotNull(victim) { "Cannot find saved manga for ${manga.title}" }
        val original = if (manga.isLocal) localContentRepository.getRemoteContent(manga) else manga
        localContentRepository.delete(victim) || throw IOException("Unable to delete file")
        runCatchingCancellable {
            historyRepository.deleteOrSwap(victim, original)
        }.onFailure {
            it.printStackTraceDebug()
        }
    }

    suspend operator fun invoke(ids: Set<Long>) {
        val list = localContentRepository.getAll()
        var removed = 0
        for (manga in list) {
            if (manga.id in ids) {
                invoke(manga)
                removed++
            }
        }
        check(removed == ids.size) {
            "Removed $removed files but ${ids.size} requested"
        }
    }
}
