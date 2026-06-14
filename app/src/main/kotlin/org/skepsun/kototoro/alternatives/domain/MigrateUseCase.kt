package org.skepsun.kototoro.alternatives.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.entitygraph.data.attachEntityOwnership
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.toContentHistory
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId
import javax.inject.Inject

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val mangaDataRepository: ContentDataRepository,
	private val database: MangaDatabase,
	private val entityGraphRepository: EntityGraphRepository,
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
		mangaDataRepository.storeContent(newDetails, replaceExisting = true)
		database.withTransaction {
			val currentTime = System.currentTimeMillis()
			// replace favorites
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldContent.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newContent.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace history
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			val newHistory =
				if (oldHistory != null) {
					val newHistory = makeNewHistory(oldDetails, newDetails, oldHistory)
					historyDao.delete(oldDetails.id)
					historyDao.upsert(newHistory)
					newHistory
				} else {
					null
			}
			// Only projection-local prefs should follow source migration.
			// Work-owned state such as metadata authority, overrides, and reading status must stay on entity/work.
			database.getPreferencesDao().find(oldDetails.id)?.let { pref ->
				database.getPreferencesDao().upsert(
					pref.copy(
						mangaId = newDetails.id,
						titleOverride = null,
						coverUrlOverride = null,
						contentRatingOverride = null,
						metadataSourceKind = null,
						metadataSourceService = null,
						metadataSourceRemoteId = null,
						readingStatus = null,
					),
				)
			}
			val localReadingBinding = entityGraphRepository.findLocalReadingBinding(oldDetails.id)
				?: entityGraphRepository.findLocalReadingBinding(newDetails.id)
			// replace tracking discovery links
			migrateTrackingLinkAnchors(
				trackingSiteDao = database.getTrackingSiteDao(),
				oldMangaId = oldDetails.id,
				newContent = newDetails,
				entityId = localReadingBinding?.entityId,
				currentTime = currentTime,
			)
			// keep the migrated content bound to the same entity graph node so source alternatives stay grouped
			localReadingBinding?.let { binding ->
				entityGraphRepository.attachLocalReadingBinding(
					entityId = binding.entityId,
					localMangaId = newDetails.id,
					confidence = binding.confidence,
				)
			}
			// track
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						ownerId = resolveTrackOwnerId(localReadingBinding?.entityId, newDetails.id),
						mangaId = newDetails.id,
						entityId = localReadingBinding?.entityId,
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
					newDetails.id,
					ScrobblerContent(
						id = prevInfo.targetId,
						name = prevInfo.title,
						altName = null,
						cover = prevInfo.coverUrl,
						url = prevInfo.externalUrl,
					),
				)
				scrobbler.updateScrobblingInfo(
					mangaId = newDetails.id,
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
						manga = newDetails,
						chapterId = newHistory.chapterId,
					)
				}
			}
		}
		progressUpdateUseCase(newDetails)
	}

	private suspend fun migrateTrackingLinkAnchors(
		trackingSiteDao: TrackingSiteDao,
		oldMangaId: Long,
		newContent: Content,
		entityId: Long?,
		currentTime: Long,
	) {
		val linksToMove = if (entityId != null) {
			trackingSiteDao.findLinksByEntity(entityId)
				.filter { it.mangaId == oldMangaId }
		} else {
			trackingSiteDao.findLinksByManga(oldMangaId)
				.groupBy { "${it.service}:${it.remoteId}" }
				.values
				.mapNotNull(::selectLegacyTrackingLinkForMigration)
		}
		if (linksToMove.isEmpty()) {
			return
		}
		linksToMove.forEach { link ->
			trackingSiteDao.deleteLink(link.service, link.remoteId, link.mangaId)
			trackingSiteDao.upsertLink(
				database.attachEntityOwnership(
					link.copy(
						entityId = entityId ?: link.entityId,
						mangaId = newContent.id,
						sourceName = newContent.source.name,
						updatedAt = currentTime,
					),
				),
			)
		}
	}

	private fun selectLegacyTrackingLinkForMigration(
		candidates: List<TrackingSiteLinkEntity>,
	): TrackingSiteLinkEntity? {
		return candidates.sortedWith(
			compareByDescending<TrackingSiteLinkEntity> { it.isManual }
				.thenByDescending { it.confidence }
				.thenByDescending { it.updatedAt },
		).firstOrNull()
	}

	private fun makeNewHistory(
		oldContent: Content,
		newContent: Content,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldContent.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newContent.getPreferredBranch(null)
			val chapters = checkNotNull(newContent.getChapters(branch))
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newContent.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
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

		return HistoryEntity(
			mangaId = newContent.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = PROGRESS_NONE,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

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
