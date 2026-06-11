package org.skepsun.kototoro.alternatives.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
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
			// replace preferences so metadata source selection and reader prefs follow the migrated title
			database.getPreferencesDao().find(oldDetails.id)?.let { pref ->
				database.getPreferencesDao().upsert(pref.copy(mangaId = newDetails.id))
			}
			// replace tracking discovery links
			val trackingSiteDao = database.getTrackingSiteDao()
			val oldTrackingLinks = trackingSiteDao.findLinksByManga(oldDetails.id)
			if (oldTrackingLinks.isNotEmpty()) {
				oldTrackingLinks.forEach { link ->
					trackingSiteDao.deleteLinksByManga(link.service, newDetails.id)
					trackingSiteDao.upsertLink(
						link.copy(
							mangaId = newDetails.id,
							sourceName = newDetails.source.name,
							updatedAt = currentTime,
						),
					)
					trackingSiteDao.deleteLink(link.service, link.remoteId, oldDetails.id)
				}
			}
			// keep the migrated content bound to the same entity graph node so source alternatives stay grouped
			entityGraphRepository.findLocalReadingBinding(oldDetails.id)?.let { binding ->
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
						mangaId = newDetails.id,
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
