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
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.data.attachEntityOwnership as attachTrackingLinkOwnership
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.data.isActiveBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.TrackingStaffDto
import org.skepsun.kototoro.entitygraph.domain.TrackingWorkDto
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.scrobbling.common.data.attachEntityOwnership as attachScrobblingOwnership
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.work.domain.WorkResolver

@HiltWorker
class EntityGraphMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: MangaDatabase,
    private val entityGraphRepository: EntityGraphRepository,
    private val favouritesRepository: FavouritesRepository,
    private val workResolver: WorkResolver,
    private val settings: AppSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val trackingSiteDao = db.getTrackingSiteDao()
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

                // 2. Bind a real local reading projection only. entity-only tracking links must not be
                //    reinterpreted as synthetic local manga bindings during migration.
                if (link.mangaId != 0L) {
                    entityGraphRepository.attachLocalReadingBinding(
                        entityId = entity.id,
                        localMangaId = link.mangaId,
                        confidence = link.confidence,
                        createdBy = EntityBindingCreatedBy.MIGRATION,
                    )
                }
            }
            // Build bindings from every legacy favourite projection first. The aggregate
            // display projection is intentionally only one item per Work and is not a
            // complete migration input.
            favouritesRepository.ensureLegacyFavouriteProjectionsForMigration()
            favouritesRepository.getAllContent().forEach { content ->
                workResolver.ensureForProjection(
                    content = content,
                    provenance = org.skepsun.kototoro.work.domain.WorkIdentityProvenance.MIGRATION,
                )
            }
            normalizeTrackingLinkOwnership()
            normalizeScrobblingOwnership()
            normalizeReadingRecordAnchors()
            entityGraphRepository.pruneRedundantProjectionMetadataSelections()

            // 3. Backfill name_hash for entities that still use the migration placeholder (name_hash = id).
            //    After Migration50To51, existing entities had name_hash set to row-id as a temporary value.
            //    This step recomputes the true normalised name hash.
            backfillNameHashes()
            settings.isLegacyFavouriteProjectionMigrationCompleted = true

            Result.success()
        } catch (e: Throwable) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun normalizeTrackingLinkOwnership() {
        val dao = db.getTrackingSiteDao()
        val normalized = dao.findAllLinks().map { db.attachTrackingLinkOwnership(it, workResolver) }
        normalized.forEach { dao.upsertLink(it) }
    }

    private suspend fun normalizeScrobblingOwnership() {
        val dao = db.getScrobblingDao()
        val normalized = dao.findAllByScrobblerEntries().map { db.attachScrobblingOwnership(it, workResolver) }
        normalized.forEach { dao.upsert(it) }
    }

    private suspend fun normalizeReadingRecordAnchors() {
        val entityDao = db.getEntityGraphDao()
        val readingDao = db.getReadingRecordDao()
        val bindingsByEntity = entityDao.dumpBindings()
            .filter { it.isActiveBinding() }
            .filter { it.source == "local_manga" || it.source == "0" }
            .groupBy { it.entityId }

        bindingsByEntity.forEach { (entityId, bindings) ->
            val localIds = bindings.mapNotNull { it.externalId.toLongOrNull() }.distinct()
            if (localIds.size < 2) {
                return@forEach
            }
            val preferredLocalId = workResolver.selectPreferredProjection(entityId)
                ?: localIds.firstOrNull()
                ?: return@forEach
            val sourceIds = localIds.filter { it != preferredLocalId }
            if (sourceIds.isEmpty()) {
                return@forEach
            }
            val sessions = readingDao.findSessions(sourceIds)
            if (sessions.isNotEmpty()) {
                sessions.forEach { session ->
                    readingDao.insertSession(session.copy(id = 0L, mangaId = preferredLocalId))
                }
                readingDao.clearSessions(sourceIds)
            }
            val jumpPoints = readingDao.findJumpPoints(sourceIds, Int.MAX_VALUE)
            if (jumpPoints.isNotEmpty()) {
                jumpPoints.forEach { jumpPoint ->
                    readingDao.insertJumpPoint(jumpPoint.copy(id = 0L, mangaId = preferredLocalId))
                }
                readingDao.clearJumpPoints(sourceIds)
            }
        }
    }

    private suspend fun backfillNameHashes() {
        val dao = db.getEntityGraphDao()
        val entities = dao.dumpEntities()
        for (record in entities) {
            val computedHash = computeNameHash(record.primaryName)
            if (record.nameHash != computedHash && record.nameHash == record.id) {
                // Only fix entities that still have the migration placeholder (name_hash == id).
                // Entities created after the migration will already have correct name_hash.
                dao.upsertEntityRecord(record.copy(nameHash = computedHash))
            }
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<EntityGraphMigrationWorker>
}
