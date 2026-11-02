package org.skepsun.kototoro.scrobbling.mangabaka.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.mangabaka.data.MangaBakaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaBakaScrobbler @Inject constructor(
    private val repository: MangaBakaRepository,
    db: MangaDatabase,
    contentRepositoryFactory: ContentRepository.Factory,
) : Scrobbler(db, ScrobblerService.MANGABAKA, repository, contentRepositoryFactory) {
    override val preservesRemoteStateOnBinding: Boolean = true

    init {
        statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
        statuses[ScrobblingStatus.READING] = "reading"
        statuses[ScrobblingStatus.RE_READING] = "rereading"
        statuses[ScrobblingStatus.COMPLETED] = "completed"
        statuses[ScrobblingStatus.ON_HOLD] = "paused"
        statuses[ScrobblingStatus.DROPPED] = "dropped"
        // Unmapped remote states (e.g. considering) remain intact until the user chooses a state.
    }

    override suspend fun updateScrobblingInfo(
        mangaId: Long, rating: Float, status: ScrobblingStatus?, comment: String?,
    ) {
        val entity = requireScrobblingEntity(mangaId)
        repository.updateRate(entity.id, entity.mangaId, rating, statuses[status], comment)
    }

    override suspend fun onAuthorized(user: ScrobblerUser) {
        repository.syncLibraryFromRemote()
    }

    override suspend fun syncLibrary(): Int = repository.syncLibraryFromRemote()
}
