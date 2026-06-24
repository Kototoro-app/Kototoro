package org.skepsun.kototoro.scrobbling.simkl.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.simkl.data.SimklRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklScrobbler @Inject constructor(
	private val repository: SimklRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: ContentRepository.Factory,
	workResolver: WorkResolver,
) : Scrobbler(db, ScrobblerService.SIMKL, repository, mangaRepositoryFactory, workResolver) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "plantowatch"
		statuses[ScrobblingStatus.READING] = "watching"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
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
		repository.syncLibraryFromRemote()
	}

	override suspend fun syncLibrary(): Int {
		return repository.syncLibraryFromRemote()
	}
}
