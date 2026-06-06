package org.skepsun.kototoro.favourites.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class AttachReadingSourceToEntityUseCase @Inject constructor(
    private val contentRepositoryFactory: ContentRepository.Factory,
    private val contentDataRepository: ContentDataRepository,
    private val database: MangaDatabase,
) {

    suspend operator fun invoke(
        oldContent: Content,
        newContent: Content,
    ): Content {
        val newDetails = if (newContent.chapters.isNullOrEmpty()) {
            contentRepositoryFactory.create(newContent.source).getDetails(newContent)
        } else {
            newContent
        }
        contentDataRepository.storeContent(newDetails, replaceExisting = true)
        var preferredProjectionId = newDetails.id
        database.withTransaction {
            val entityId = resolveOrCreateEntityId(oldContent)
            val bindingDao = database.getEntityGraphDao()
            val existingProjectionForSource = findEntityProjectionBySource(
                entityId = entityId,
                sourceName = newDetails.source.name,
            )
            preferredProjectionId = existingProjectionForSource?.id ?: newDetails.id
            val existingBinding = findLocalBinding(newDetails.id)
            if (existingProjectionForSource == null && existingBinding == null) {
                val confidence = findLocalBinding(oldContent.id)?.confidence ?: 1f
                bindingDao.upsertBinding(
                    EntityBindingRecord(
                        entityId = entityId,
                        source = "local_manga",
                        externalId = newDetails.id.toString(),
                        confidence = confidence,
                        isPrimary = false,
                    ),
                )
            }
            contentDataRepository.setEntityPreferredLocalMangaId(
                entityId = entityId,
                mangaId = preferredProjectionId,
            )
            contentDataRepository.setEntityMetadataSourceSelection(
                entityId = entityId,
                selection = ContentDataRepository.MetadataSourceSelection.Base,
                mirrorLocalMangaIds = listOf(preferredProjectionId),
            )
        }
        return runCatchingCancellable {
            contentDataRepository.findContentById(preferredProjectionId, withChapters = false)
        }.getOrNull() ?: newDetails
    }

    private suspend fun resolveOrCreateEntityId(content: Content): Long {
        findLocalBinding(content.id)?.let { return it.entityId }
        val now = System.currentTimeMillis()
        val entityId = database.getEntityGraphDao().insertEntity(
            EntityRecord(
                type = EntityType.WORK.name,
                primaryName = content.title.trim(),
                aliases = null,
                createdAt = now,
                lastAccessed = now,
                accessCount = 1,
            ),
        )
        database.getEntityGraphDao().upsertBinding(
            EntityBindingRecord(
                entityId = entityId,
                source = "local_manga",
                externalId = content.id.toString(),
                confidence = 1f,
                isPrimary = true,
            ),
        )
        return entityId
    }

    private suspend fun findLocalBinding(mangaId: Long): EntityBindingRecord? {
        val dao = database.getEntityGraphDao()
        return dao.findBinding("local_manga", mangaId.toString())
            ?: dao.findBinding("0", mangaId.toString())
    }

    private suspend fun findEntityProjectionBySource(
        entityId: Long,
        sourceName: String,
    ): Content? {
        val bindings = database.getEntityGraphDao().findBindingsByEntity(entityId)
        for (binding in bindings) {
            if (binding.source != "local_manga" && binding.source != "0") {
                continue
            }
            val mangaId = binding.externalId.toLongOrNull() ?: continue
            val content = database.getMangaDao().find(mangaId)?.toContent() ?: continue
            if (content.source.name == sourceName) {
                return content
            }
        }
        return null
    }
}
