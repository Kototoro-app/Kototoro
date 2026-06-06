package org.skepsun.kototoro.entitygraph.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.json.JSONArray
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.TrackingStaffDto
import org.skepsun.kototoro.entitygraph.domain.TrackingWorkDto
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

@HiltWorker
class EntityGraphMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: MangaDatabase,
    private val entityGraphRepository: EntityGraphRepository,
    private val favouritesRepository: FavouritesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val trackingSiteDao = db.getTrackingSiteDao()
            val entityGraphDao = db.getEntityGraphDao()

            val allLinks = trackingSiteDao.findAllLinks()
            
            for (link in allLinks) {
                val service = ScrobblerService.entries.find { it.id == link.service } ?: continue
                val item = trackingSiteDao.findItem(service.id, link.remoteId) ?: continue
                
                val aliases = runCatching {
                    val array = JSONArray(item.altTitles ?: "[]")
                    List(array.length()) { array.optString(it) }
                }.getOrDefault(emptyList())

                val authors = runCatching {
                    val array = JSONArray(item.authors ?: "[]")
                    List(array.length()) { array.optString(it) }
                }.getOrDefault(emptyList())

                val workDto = TrackingWorkDto(
                    externalId = link.remoteId.toString(),
                    primaryName = item.title,
                    aliases = aliases,
                    characters = emptyList(), // Not cached in classic TrackingSiteItemEntity
                    staff = authors.map { TrackingStaffDto(primaryName = it) }
                )

                // 1. Unify the tracked work into the graph
                val entity = entityGraphRepository.ingestWorkFromTracking(
                    source = service.name.lowercase(),
                    workDto = workDto
                )

                // 2. Bind the local manga to this entity graph root node!
                entityGraphDao.upsertBinding(
                    EntityBindingRecord(
                        entityId = entity.id,
                        source = "local_manga",
                        externalId = link.mangaId.toString(),
                        confidence = link.confidence,
                        isPrimary = false
                    )
                )
            }
            entityGraphRepository.ensureLocalWorkEntities(favouritesRepository.getAllContent())
            Result.success()
        } catch (e: Throwable) {
            e.printStackTrace()
            Result.failure()
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<EntityGraphMigrationWorker>
}
