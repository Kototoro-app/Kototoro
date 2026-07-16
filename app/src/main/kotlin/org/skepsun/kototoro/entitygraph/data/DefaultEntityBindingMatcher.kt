package org.skepsun.kototoro.entitygraph.data

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBindingMatcher
import org.skepsun.kototoro.entitygraph.domain.EntityBindingStrength
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.RelationType
import javax.inject.Inject

private const val AUTO_BIND_THRESHOLD = 0.90f
private const val WEAK_BIND_THRESHOLD = 0.65f
private const val SAME_WORK_BONUS = 0.08f
private const val SAME_CHARACTER_BONUS = 0.08f
private const val SAME_CREATED_WORK_BONUS = 0.05f

@Reusable
class DefaultEntityBindingMatcher @Inject constructor(
	private val db: MangaDatabase,
) : EntityBindingMatcher {

	override suspend fun tryBindEntities(entityA: Entity, entityB: Entity): Float {
		if (entityA.type != entityB.type) {
			return 0f
		}
		if (entityA.type == EntityType.WORK &&
			!entityA.canAutoBindContentType(entityB.contentType)) {
			return 0f
		}
		val nameScore = scoreNames(entityA, entityB)
		if (nameScore <= 0f) {
			return 0f
		}
		val contextScore = scoreContext(entityA, entityB)
		return (nameScore + contextScore).coerceIn(0f, 1f)
	}

	override fun classify(confidence: Float): EntityBindingStrength = when {
		confidence > AUTO_BIND_THRESHOLD -> EntityBindingStrength.AUTO_BIND
		confidence >= WEAK_BIND_THRESHOLD -> EntityBindingStrength.WEAK_BIND
		else -> EntityBindingStrength.IGNORE
	}

	private suspend fun scoreContext(entityA: Entity, entityB: Entity): Float {
		if (entityA.id <= 0L || entityB.id <= 0L) {
			return 0f
		}
		val dao = db.getEntityGraphDao()
		return when (entityA.type) {
			EntityType.CHARACTER -> {
				val worksA = dao.findVisibleIncomingEntityIds(entityA.id, RelationType.HAS_CHARACTER.name).toSet()
				val worksB = dao.findVisibleIncomingEntityIds(entityB.id, RelationType.HAS_CHARACTER.name).toSet()
				if (worksA.isNotEmpty() && worksA.intersect(worksB).isNotEmpty()) {
					SAME_WORK_BONUS
				} else {
					0f
				}
			}

			EntityType.PERSON -> {
				var score = 0f
				val charactersA = dao.findVisibleIncomingEntityIds(entityA.id, RelationType.VOICED_BY.name).toSet()
				val charactersB = dao.findVisibleIncomingEntityIds(entityB.id, RelationType.VOICED_BY.name).toSet()
				if (charactersA.isNotEmpty() && charactersA.intersect(charactersB).isNotEmpty()) {
					score += SAME_CHARACTER_BONUS
				}
				val worksA = dao.findVisibleIncomingEntityIds(entityA.id, RelationType.CREATED_BY.name).toSet()
				val worksB = dao.findVisibleIncomingEntityIds(entityB.id, RelationType.CREATED_BY.name).toSet()
				if (worksA.isNotEmpty() && worksA.intersect(worksB).isNotEmpty()) {
					score += SAME_CREATED_WORK_BONUS
				}
				score
			}

			else -> 0f
		}
	}

	private fun scoreNames(entityA: Entity, entityB: Entity): Float {
		val namesA = mergeAliases(entityA.primaryName, entityA.aliases)
		val namesB = mergeAliases(entityB.primaryName, entityB.aliases)

		val aSet = namesA.toSet()
		if (namesB.any { it in aSet }) return 1f

		val aLower = namesA.mapTo(HashSet(namesA.size)) { it.lowercase() }
		if (namesB.any { it.lowercase() in aLower }) return 1f

		return 0f
	}
}
