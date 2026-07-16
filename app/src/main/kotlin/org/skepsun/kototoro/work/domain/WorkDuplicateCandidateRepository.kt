package org.skepsun.kototoro.work.domain

import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_DETACH
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_SPLIT
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_TABLE
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_STATUS_ACTIVE
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject

@Reusable
class WorkDuplicateCandidateRepository @Inject constructor(
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) {

	suspend fun findCandidates(content: Content): List<WorkDuplicateCandidate> = withContext(Dispatchers.IO) {
		val currentIdentity = workResolver.resolveByMangaId(content.id)
		val projectionActionCandidate = findProjectionActionCandidate(
			localMangaId = content.id,
			currentEntityId = currentIdentity.entityId,
			contentType = content.source.contentType,
		)
		val titleHashes = content.identityTitleHashes()
		if (titleHashes.isEmpty()) {
			return@withContext listOfNotNull(projectionActionCandidate)
		}

		val dao = db.getEntityGraphDao()
		val entities = titleHashes
			.chunked(MAX_DUPLICATE_QUERY_PARAMS)
			.flatMap { dao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, it) }
			.distinctBy { it.id }
			.filterNot { it.id == currentIdentity.entityId }
			.filter { it.contentType == content.source.contentType.name }
		val titleCandidates = entities.map { entity ->
			val bindings = dao.findActiveLocalBindingsByEntity(entity.id)
			val projectionIds = bindings.mapNotNullTo(LinkedHashSet()) { it.externalId.toLongOrNull() }
			val projections = projectionIds.mapNotNull { db.getMangaDao().find(it)?.toContent() }
			WorkDuplicateCandidate(
				entityId = entity.id,
				title = entity.primaryName,
				sourceLabels = projections.mapTo(LinkedHashSet()) { it.source.name }.toList(),
				projectionCount = projectionIds.size,
				reason = candidateReason(content, projections),
				mergeBackTargetEntityId = entity.id,
			)
		}
		(listOfNotNull(projectionActionCandidate) + titleCandidates)
			.distinctBy { it.entityId to it.reason }
			.sortedWith(
			compareBy<WorkDuplicateCandidate> { it.reason.rank }
				.thenByDescending { it.projectionCount }
				.thenBy { it.title },
		)
	}

	private suspend fun findProjectionActionCandidate(
		localMangaId: Long,
		currentEntityId: Long?,
		contentType: ContentType,
	): WorkDuplicateCandidate? {
		val ledger = db.getWorkMigrationLedgerDao().findLatest(
			legacyTable = WORK_PROJECTION_IDENTITY_ACTION_TABLE,
			legacyKey = localMangaId.toString(),
		)?.takeIf { it.status == WORK_PROJECTION_IDENTITY_STATUS_ACTIVE } ?: return null
		val parts = ledger.legacyChecksum.orEmpty().split(":", limit = 3)
		val originalEntityId = parts.getOrNull(0)?.toLongOrNull() ?: return null
		val detachedEntityId = ledger.targetEntityId ?: parts.getOrNull(1)?.toLongOrNull() ?: return null
		if (detachedEntityId != currentEntityId) {
			return null
		}
		val action = parts.getOrNull(2).orEmpty()
		val original = db.getEntityGraphDao().findEntity(originalEntityId)
			?.takeIf { it.contentType == contentType.name }
			?: return null
		return WorkDuplicateCandidate(
			entityId = original.id,
			title = original.primaryName,
			sourceLabels = emptyList(),
			projectionCount = db.getEntityGraphDao().findActiveLocalBindingsByEntity(original.id).size,
			reason = when (action) {
				WORK_PROJECTION_IDENTITY_ACTION_DETACH -> WorkDuplicateCandidateReason.PREVIOUSLY_DETACHED
				WORK_PROJECTION_IDENTITY_ACTION_SPLIT -> WorkDuplicateCandidateReason.PREVIOUSLY_SPLIT
				else -> WorkDuplicateCandidateReason.PREVIOUSLY_DETACHED
			},
			mergeBackTargetEntityId = original.id,
		)
	}

	private fun Content.identityTitleHashes(): List<Long> {
		return (listOf(title) + altTitles)
			.asSequence()
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.map { computeNameHash(it) }
			.distinct()
			.toList()
	}

	private fun candidateReason(
		content: Content,
		projections: List<Content>,
	): WorkDuplicateCandidateReason {
		return if (projections.any { projection ->
				ProjectionIdentityKeys.hasSameIdentity(
					source = content.source.name,
					url = content.url,
					publicUrl = content.publicUrl,
					otherSource = projection.source.name,
					otherUrl = projection.url,
					otherPublicUrl = projection.publicUrl,
				) || ProjectionIdentityKeys.contentCompactKey(
					source = content.source.name,
					id = content.id,
					url = content.url,
					publicUrl = content.publicUrl,
				) == ProjectionIdentityKeys.contentCompactKey(
					source = projection.source.name,
					id = projection.id,
					url = projection.url,
					publicUrl = projection.publicUrl,
				)
			}
		) {
			WorkDuplicateCandidateReason.SAME_PROJECTION
		} else {
			WorkDuplicateCandidateReason.TITLE_MATCH
		}
	}

	private val WorkDuplicateCandidateReason.rank: Int
		get() = when (this) {
			WorkDuplicateCandidateReason.PREVIOUSLY_DETACHED -> 0
			WorkDuplicateCandidateReason.PREVIOUSLY_SPLIT -> 0
			WorkDuplicateCandidateReason.SAME_PROJECTION -> 1
			WorkDuplicateCandidateReason.TITLE_MATCH -> 2
		}

	private companion object {
		private const val MAX_DUPLICATE_QUERY_PARAMS = 500
	}
}

data class WorkDuplicateCandidate(
	val entityId: Long,
	val title: String,
	val sourceLabels: List<String>,
	val projectionCount: Int,
	val reason: WorkDuplicateCandidateReason,
	val mergeBackTargetEntityId: Long? = null,
)

enum class WorkDuplicateCandidateReason {
	PREVIOUSLY_DETACHED,
	PREVIOUSLY_SPLIT,
	SAME_PROJECTION,
	TITLE_MATCH,
}
