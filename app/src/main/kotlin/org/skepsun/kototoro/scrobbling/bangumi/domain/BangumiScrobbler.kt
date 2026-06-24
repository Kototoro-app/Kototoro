package org.skepsun.kototoro.scrobbling.bangumi.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiRepository
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BangumiScrobbler @Inject constructor(
	private val repository: BangumiRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: ContentRepository.Factory,
	workResolver: WorkResolver,
) : Scrobbler(db, ScrobblerService.BANGUMI, repository, mangaRepositoryFactory, workResolver) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "wish"
		statuses[ScrobblingStatus.READING] = "do"
		statuses[ScrobblingStatus.RE_READING] = "do"
		statuses[ScrobblingStatus.COMPLETED] = "collect"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
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
