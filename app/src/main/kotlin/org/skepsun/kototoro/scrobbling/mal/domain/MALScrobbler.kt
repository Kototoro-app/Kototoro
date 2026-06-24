package org.skepsun.kototoro.scrobbling.mal.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.mal.data.MALRepository
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

private const val RATING_MAX = 10f

@Singleton
class MALScrobbler @Inject constructor(
	private val repository: MALRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: ContentRepository.Factory,
	workResolver: WorkResolver,
) : Scrobbler(db, ScrobblerService.MAL, repository, mangaRepositoryFactory, workResolver) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
		statuses[ScrobblingStatus.READING] = "reading"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	override suspend fun getContentInfo(entity: ScrobblingEntity) =
		repository.getContentPreview(entity.targetId, entity.mangaId, entity.mediaType)

	override suspend fun fallbackScrobblingInfo(entity: ScrobblingEntity): ScrobblingInfo? {
		val localManga = db.getMangaDao().find(entity.mangaId)?.manga
		val localTitle = localManga?.title
		val endpoint = entity.mediaType.takeIf { it.isNotBlank() }
			?: localManga?.source
				?.let(::ContentSource)
				?.getContentType()
				?.let { type ->
					if (type == ContentType.VIDEO || type == ContentType.HENTAI_VIDEO) "anime" else "manga"
				}
			?: "manga"
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			entityId = entity.entityId,
			preferredLocalMangaId = entity.mangaId.takeIf { it != 0L },
			mangaId = entity.mangaId,
			targetId = entity.targetId,
			status = resolveStatus(entity.status),
			chapter = entity.chapter,
			comment = entity.comment,
			rating = entity.rating,
			title = localTitle ?: "MAL #${entity.targetId}",
			coverUrl = "",
			description = null,
			externalUrl = "https://myanimelist.net/$endpoint/${entity.targetId}",
			mediaType = entity.mediaType.takeIf { it.isNotBlank() } ?: endpoint,
		)
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
			rating = rating * RATING_MAX,
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
