package org.skepsun.kototoro.alternatives.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanner
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject

/**
 * Moves (or copies) one entry's user data onto another entry. Everything is written in a
 * single transaction; the old manga row itself is never deleted so local downloads and
 * foreign-key children that migration does not carry (bookmarks) survive.
 */
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
        mode: MigrationMode = MigrationMode.REPLACE,
        flags: Set<MigrationDataFlag> = MigrationDataFlag.ALL,
    ) {
        val oldDetails = if (oldContent.chapters.isNullOrEmpty()) {
            mangaDataRepository.findContentById(oldContent.id, withChapters = true)
                ?.takeUnless { it.chapters.isNullOrEmpty() }
                ?: runCatchingCancellable {
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
        val stored = mangaDataRepository.storeContentAndReturn(newDetails, replaceExisting = true)
        val migrationDao = database.getMigrationDao()
        val oldId = oldDetails.id
        val plan = database.withTransaction {
            val snapshot = MigrationSnapshot(
                favourites = database.getFavouritesDao().findActiveByMangaId(oldId),
                history = database.getHistoryDao().find(oldId),
                prefs = database.getPreferencesDao().find(oldId),
                trackingLinks = database.getTrackingSiteDao().findLinksByManga(oldId),
                track = database.getTracksDao().find(oldId),
                notes = migrationDao.findNotes(oldId),
                sessions = migrationDao.findSessions(oldId),
                jumpPoints = migrationDao.findJumpPoints(oldId),
            )
            val plan = MigrationPlanner.plan(oldDetails, stored, snapshot, mode, flags, System.currentTimeMillis())

            val favouritesDao = database.getFavouritesDao()
            if (plan.deleteOldFavourites) favouritesDao.delete(oldId)
            plan.favouritesToUpsert.forEach { favouritesDao.upsert(it) }

            val historyDao = database.getHistoryDao()
            if (plan.deleteOldHistory) historyDao.delete(oldId)
            plan.historyToUpsert?.let { historyDao.upsert(it) }

            plan.prefsToUpsert?.let { database.getPreferencesDao().upsert(it) }

            val trackingSiteDao = database.getTrackingSiteDao()
            plan.trackingLinksToDelete.forEach { trackingSiteDao.deleteLink(it.service, it.remoteId, it.mangaId) }
            plan.trackingLinksToUpsert.forEach { trackingSiteDao.upsertLink(it) }

            val tracksDao = database.getTracksDao()
            if (plan.deleteOldTrack) tracksDao.delete(oldId)
            plan.trackToUpsert?.let { tracksDao.upsert(it) }

            if (plan.notesToUpdate.isNotEmpty()) migrationDao.updateNotes(plan.notesToUpdate)
            if (plan.notesToInsert.isNotEmpty()) migrationDao.insertNotes(plan.notesToInsert)
            if (plan.moveStats) migrationDao.moveStats(oldId, stored.id)
            if (plan.sessionsToUpdate.isNotEmpty()) migrationDao.updateSessions(plan.sessionsToUpdate)
            if (plan.jumpPointsToUpdate.isNotEmpty()) migrationDao.updateJumpPoints(plan.jumpPointsToUpdate)
            plan
        }
        if (MigrationDataFlag.TRACKING in flags) {
            migrateScrobbling(oldId, stored, mode, plan.historyToUpsert?.chapterId, plan.historyToUpsert?.percent)
        }
        progressUpdateUseCase(stored)
    }

    private suspend fun migrateScrobbling(
        oldId: Long,
        stored: Content,
        mode: MigrationMode,
        historyChapterId: Long?,
        historyPercent: Float?,
    ) {
        for (scrobbler in scrobblers) {
            if (!scrobbler.isEnabled) continue
            val prevInfo = scrobbler.getScrobblingInfoOrNull(oldId) ?: continue
            if (mode == MigrationMode.REPLACE) {
                scrobbler.unregisterScrobbling(oldId)
            }
            scrobbler.linkContent(
                stored.id,
                ScrobblerContent(
                    id = prevInfo.targetId,
                    name = prevInfo.title,
                    altName = null,
                    cover = prevInfo.coverUrl,
                    url = prevInfo.externalUrl,
                ),
            )
            scrobbler.updateScrobblingInfo(
                mangaId = stored.id,
                rating = prevInfo.rating,
                status = prevInfo.status ?: when {
                    historyChapterId == null -> ScrobblingStatus.PLANNED
                    historyPercent == 1f -> ScrobblingStatus.COMPLETED
                    else -> ScrobblingStatus.READING
                },
                comment = prevInfo.comment,
            )
            if (historyChapterId != null) {
                scrobbler.scrobble(manga = stored, chapterId = historyChapterId)
            }
        }
    }
}
