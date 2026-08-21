package org.skepsun.kototoro.history.domain

import dagger.Reusable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@Reusable
class MarkAsReadUseCase @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val mangaRepositoryFactory: ContentRepository.Factory,
) {

    suspend operator fun invoke(manga: Content) {
        val repo = mangaRepositoryFactory.create(manga.source)
        val details = if (manga.chapters.isNullOrEmpty()) {
            repo.getDetails(manga)
        } else {
            manga
        }
        val lastChapter = checkNotNull(details.chapters).last()
        val pages = repo.getPages(lastChapter)
        historyRepository.addOrUpdate(
            manga = details,
            chapterId = lastChapter.id,
            page = pages.lastIndex,
            scroll = 0,
            percent = 1f,
            force = true,
        )
    }

    suspend operator fun invoke(manga: Collection<Content>) {
        when (manga.size) {
            0 -> Unit
            1 -> invoke(manga.first())
            else -> supervisorScope {
                manga.map {
                    launch {
                        invoke(it)
                    }
                }.joinAll()
            }
        }
    }
}
