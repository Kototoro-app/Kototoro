package org.skepsun.kototoro.scrobbling.kitsu.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuScrobbler @Inject constructor(
	private val repository: KitsuRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: ContentRepository.Factory,
	workResolver: WorkResolver,
) : Scrobbler(db, ScrobblerService.KITSU, repository, mangaRepositoryFactory, workResolver) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "planned"
		statuses[ScrobblingStatus.READING] = "current"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	override suspend fun getContentInfo(entity: ScrobblingEntity) =
		repository.getContentInfo(entity.targetId, entity.mangaId).also { info ->
			repository.persistPreview(
				mangaId = entity.mangaId,
				targetId = entity.targetId,
				mediaType = entity.mediaType,
				title = info.name,
				coverUrl = info.cover,
				url = info.url,
			)
		}

	override suspend fun updateScrobblingInfo(
		mangaId: Long,
		rating: Float,
		status: ScrobblingStatus?,
		comment: String?
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

	protected override suspend fun onAuthorized(user: ScrobblerUser) {
		repository.syncLibraryFromRemote()
	}

	override suspend fun syncLibrary(): Int {
		return repository.syncLibraryFromRemote()
	}

}
