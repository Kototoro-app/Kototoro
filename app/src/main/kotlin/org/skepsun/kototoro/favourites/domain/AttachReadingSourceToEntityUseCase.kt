package org.skepsun.kototoro.favourites.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.data.isLocalReadingSource
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

class AttachReadingSourceToEntityUseCase @Inject constructor(
    private val contentRepositoryFactory: ContentRepository.Factory,
    private val contentDataRepository: ContentDataRepository,
    private val database: MangaDatabase,
    private val entityGraphRepository: EntityGraphRepository,
    private val workResolver: WorkResolver,
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
        val storedDetails = contentDataRepository.storeContentAndReturn(newDetails, replaceExisting = true)
        var preferredProjectionId = storedDetails.id
        database.withTransaction {
            val entityId = resolveOrCreateEntityId(oldContent)
            val existingProjectionForSource = findEntityProjectionBySource(
                entityId = entityId,
                sourceName = storedDetails.source.name,
            )
            preferredProjectionId = existingProjectionForSource?.id ?: storedDetails.id
            val existingBinding = findLocalBinding(storedDetails.id)
            if (existingProjectionForSource == null && existingBinding == null) {
                val confidence = findLocalBinding(oldContent.id)?.confidence ?: 1f
                entityGraphRepository.attachLocalReadingBinding(
                    entityId = entityId,
                    localMangaId = storedDetails.id,
                    confidence = confidence,
                )
            }
            contentDataRepository.setEntityPreferredLocalMangaId(
                entityId = entityId,
                mangaId = preferredProjectionId,
            )
            contentDataRepository.setEntityMetadataSourceSelection(
                entityId = entityId,
                selection = ContentDataRepository.MetadataSourceSelection.Base,
            )
        }
        return runCatchingCancellable {
            contentDataRepository.findContentById(preferredProjectionId, withChapters = false)
        }.getOrNull() ?: storedDetails
    }

    private suspend fun resolveOrCreateEntityId(content: Content): Long {
        return requireNotNull(
            workResolver.ensureForProjection(
                content = content,
                provenance = WorkIdentityProvenance.USER,
            ).entityId,
        )
    }

    private suspend fun findLocalBinding(mangaId: Long): EntityBinding? {
        return entityGraphRepository.findLocalReadingBinding(mangaId)
    }

    private suspend fun findEntityProjectionBySource(
        entityId: Long,
        sourceName: String,
    ): Content? {
        val bindings = database.getEntityGraphDao().findActiveBindingsByEntity(entityId)
        for (binding in bindings) {
            if (!binding.isLocalReadingSource()) {
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
