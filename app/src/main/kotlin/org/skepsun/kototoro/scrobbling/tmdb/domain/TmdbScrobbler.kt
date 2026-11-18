package org.skepsun.kototoro.scrobbling.tmdb.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.tmdb.data.TmdbRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB keeps a watchlist and ratings, not watch progress: planned titles go to the watchlist and the rest leave it,
 * which is the whole status vocabulary the account has.
 */
@Singleton
class TmdbScrobbler @Inject constructor(
    private val repository: TmdbRepository,
    db: MangaDatabase,
    mangaRepositoryFactory: ContentRepository.Factory,
) : Scrobbler(db, ScrobblerService.TMDB, repository, mangaRepositoryFactory) {

    init {
        statuses[ScrobblingStatus.PLANNED] = TmdbRepository.STATUS_WATCHLIST
        statuses[ScrobblingStatus.READING] = TmdbRepository.STATUS_WATCHING
        statuses[ScrobblingStatus.COMPLETED] = TmdbRepository.STATUS_WATCHED
        statuses[ScrobblingStatus.ON_HOLD] = TmdbRepository.STATUS_WATCHING
        statuses[ScrobblingStatus.DROPPED] = TmdbRepository.STATUS_NONE
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
}
