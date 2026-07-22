package org.skepsun.kototoro.scrobbling.mangaupdates.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaUpdatesScrobbler @Inject constructor(
	private val repository: MangaUpdatesRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: ContentRepository.Factory,
	workResolver: WorkResolver,
) : Scrobbler(db, ScrobblerService.MANGAUPDATES, repository, mangaRepositoryFactory, workResolver) {

	init {
		statuses[ScrobblingStatus.READING] = "0"
		statuses[ScrobblingStatus.PLANNED] = "1"
		statuses[ScrobblingStatus.COMPLETED] = "2"
		statuses[ScrobblingStatus.ON_HOLD] = "4"
		statuses[ScrobblingStatus.DROPPED] = "3" // "Unfinished" mapping
	}

	override suspend fun updateScrobblingInfo(
		mangaId: Long,
		rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
	) {
		val entity = requireScrobblingEntity(mangaId)
		
		repository.updateRate(
			rateId = entity.id,
			mangaId = entity.mangaId,
			rating = rating,
			status = statuses[status],
			comment = comment,
		)
	}

	override suspend fun onAuthorized(user: ScrobblerUser) {
		// Sync functionality not completely ported for MU, but let's call it just in case
		// repository.syncLibraryFromRemote()
	}

	override suspend fun warmUpScrobblingInfoInternal(info: ScrobblingInfo) {
		if (info.coverUrl.isNotBlank()) return
		repository.persistRemoteCoverIfMissing(info.targetId)
	}

	override suspend fun syncLibrary(): Int {
		return repository.syncLibraryFromRemote()
	}
}
