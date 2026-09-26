package org.skepsun.kototoro.alternatives.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.tracker.data.TrackEntity
import java.time.Instant
import javax.inject.Inject

class MigrateUseCase @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val mangaDataRepository: ContentDataRepository,
    private val database: MangaDatabase,
    private val progressUpdateUseCase: ProgressUpdateUseCase,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {
    suspend operator fun invoke(
        oldContent: Content,
        newContent: Content,
    ) {
        val oldDetails = if (oldContent.chapters.isNullOrEmpty()) {
            runCatchingCancellable {
                mangaRepositoryFactory.create(oldContent.source).getDetails(oldContent)
            }.getOrDefault(oldContent)
        } else {
            oldContent
        }
        val newDetails = if (newContent.chapters.isNullOrEmpty()) {
            mangaRepositoryFactory.create(newContent.source).getDetails(newContent)
        } else {
            newContent
        }
        val storedNewDetails = mangaDataRepository.storeContentAndReturn(newDetails, replaceExisting = true)
        database.withTransaction {
            val currentTime = System.currentTimeMillis()

            // replace favorites
            val favouritesDao = database.getFavouritesDao()
            val oldFavourites = favouritesDao.findActiveByMangaId(oldDetails.id)
            if (oldFavourites.isNotEmpty()) {
                favouritesDao.delete(oldDetails.id)
                for (favourite in oldFavourites) {
                    favouritesDao.upsert(
                        favourite.copy(
                            mangaId = storedNewDetails.id,
                            updatedAt = currentTime,
                        ),
                    )
                }
            }

            // replace history
            val historyDao = database.getHistoryDao()
            val oldHistory = historyDao.find(oldDetails.id)
            val newHistory = if (oldHistory != null) {
                val newHistory = makeNewHistory(oldDetails, storedNewDetails, oldHistory)
                historyDao.delete(oldDetails.id)
                historyDao.upsert(newHistory)
                newHistory
            } else {
                null
            }

            // replace preferences
            val preferencesDao = database.getPreferencesDao()
            preferencesDao.find(oldDetails.id)?.let { pref ->
                preferencesDao.upsert(
                    pref.copy(
                        mangaId = storedNewDetails.id,
                    ),
                )
            }

            // replace tracking discovery links
            val trackingSiteDao = database.getTrackingSiteDao()
            val oldLinks = trackingSiteDao.findLinksByManga(oldDetails.id)
            for (link in oldLinks) {
                trackingSiteDao.deleteLink(link.service, link.remoteId, link.mangaId)
                trackingSiteDao.upsertLink(
                    link.copy(
                        mangaId = storedNewDetails.id,
                        sourceName = storedNewDetails.source.name,
                        updatedAt = currentTime,
                    ),
                )
            }

            // replace track
            val tracksDao = database.getTracksDao()
            val oldTrack = tracksDao.find(oldDetails.id)
            if (oldTrack != null) {
                val lastChapter = storedNewDetails.chapters?.lastOrNull()
                val newTrack = TrackEntity(
                    mangaId = storedNewDetails.id,
                    lastChapterId = lastChapter?.id ?: 0L,
                    newChapters = 0,
                    lastCheckTime = currentTime,
                    lastChapterDate = lastChapter?.uploadDate ?: 0L,
                    lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
                    lastError = null,
                )
                tracksDao.delete(oldDetails.id)
                tracksDao.upsert(newTrack)
            }

            // scrobbling
            for (scrobbler in scrobblers) {
                if (!scrobbler.isEnabled) {
                    continue
                }
                val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: continue
                scrobbler.unregisterScrobbling(oldDetails.id)
                scrobbler.linkContent(
                    storedNewDetails.id,
                    ScrobblerContent(
                        id = prevInfo.targetId,
                        name = prevInfo.title,
                        altName = null,
                        cover = prevInfo.coverUrl,
                        url = prevInfo.externalUrl,
                    ),
                )
                scrobbler.updateScrobblingInfo(
                    mangaId = storedNewDetails.id,
                    rating = prevInfo.rating,
                    status =
                        prevInfo.status ?: when {
                            newHistory == null -> ScrobblingStatus.PLANNED
                            newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
                            else -> ScrobblingStatus.READING
                        },
                    comment = prevInfo.comment,
                )
                if (newHistory != null) {
                    scrobbler.scrobble(
                        manga = storedNewDetails,
                        chapterId = newHistory.chapterId,
                    )
                }
            }
        }
        progressUpdateUseCase(storedNewDetails)
    }

    private fun makeNewHistory(
        oldContent: Content,
        newContent: Content,
        history: HistoryEntity,
    ): HistoryEntity {
        if (oldContent.chapters.isNullOrEmpty()) {
            val branch = newContent.getPreferredBranch(null)
            val chapters = checkNotNull(newContent.getChapters(branch))
            val currentChapter =
                if (history.percent in 0f..1f) {
                    chapters[(chapters.lastIndex * history.percent).toInt()]
                } else {
                    chapters.first()
                }
            return history.copy(
                mangaId = newContent.id,
                chapterId = currentChapter.id,
                page = history.page,
                scroll = history.scroll,
                percent = history.percent,
                deletedAt = 0,
                chaptersCount = chapters.count { it.branch == currentChapter.branch },
            )
        }
        val branch = oldContent.getPreferredBranch(history.toContentHistory())
        val oldChapters = checkNotNull(oldContent.getChapters(branch))
        var index = oldChapters.indexOfFirst { it.id == history.chapterId }
        if (index < 0) {
            index =
                if (history.percent in 0f..1f) {
                    (oldChapters.lastIndex * history.percent).toInt()
                } else {
                    0
                }
        }
        val newChapters = checkNotNull(newContent.chapters).groupBy { it.branch }
        val newBranch =
            if (newChapters.containsKey(branch)) {
                branch
            } else {
                newContent.getPreferredBranch(null)
            }
        val newChapterId =
            checkNotNull(newChapters[newBranch])
                .let {
                    val oldChapter = oldChapters[index]
                    it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
                }.id

        return history.copy(
            mangaId = newContent.id,
            chapterId = newChapterId,
            page = history.page,
            scroll = history.scroll,
            percent = PROGRESS_NONE,
            deletedAt = 0,
            chaptersCount = checkNotNull(newChapters[newBranch]).size,
        )
    }

    private fun HistoryEntity.toContentHistory() = ContentHistory(
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        chapterId = chapterId,
        page = page,
        scroll = scroll.toInt(),
        percent = percent,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )

    private fun List<ContentChapter>.findByNumber(
        volume: Int,
        number: Float,
    ): ContentChapter? =
        if (number <= 0f) {
            null
        } else {
            firstOrNull { it.volume == volume && it.number == number }
        }
}
