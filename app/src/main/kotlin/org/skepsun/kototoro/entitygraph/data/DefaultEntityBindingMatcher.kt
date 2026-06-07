package org.skepsun.kototoro.entitygraph.data

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBindingMatcher
import org.skepsun.kototoro.entitygraph.domain.EntityBindingStrength
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.RelationType
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import javax.inject.Inject

private const val AUTO_BIND_THRESHOLD = 0.85f
private const val WEAK_BIND_THRESHOLD = 0.60f
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
				val worksA = dao.findIncomingEntityIds(entityA.id, RelationType.HAS_CHARACTER.name).toSet()
				val worksB = dao.findIncomingEntityIds(entityB.id, RelationType.HAS_CHARACTER.name).toSet()
				if (worksA.isNotEmpty() && worksA.intersect(worksB).isNotEmpty()) {
					SAME_WORK_BONUS
				} else {
					0f
				}
			}

			EntityType.PERSON -> {
				var score = 0f
				val charactersA = dao.findIncomingEntityIds(entityA.id, RelationType.VOICED_BY.name).toSet()
				val charactersB = dao.findIncomingEntityIds(entityB.id, RelationType.VOICED_BY.name).toSet()
				if (charactersA.isNotEmpty() && charactersA.intersect(charactersB).isNotEmpty()) {
					score += SAME_CHARACTER_BONUS
				}
				val worksA = dao.findIncomingEntityIds(entityA.id, RelationType.CREATED_BY.name).toSet()
				val worksB = dao.findIncomingEntityIds(entityB.id, RelationType.CREATED_BY.name).toSet()
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
		var best = 0f
		for (left in namesA) {
			for (right in namesB) {
				val score = scoreName(left, right)
				if (score > best) {
					best = score
				}
			}
		}
		return best
	}

	private fun scoreName(left: String, right: String): Float {
		if (left == right) {
			return 1f
		}
		if (left.equals(right, ignoreCase = true)) {
			return 0.9f
		}
		val normalizedLeft = normalizeName(left)
		val normalizedRight = normalizeName(right)
		if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
			return 0f
		}
		if (normalizedLeft == normalizedRight) {
			return 0.9f
		}
		val maxLength = maxOf(normalizedLeft.length, normalizedRight.length).coerceAtLeast(1)
		val similarity = 1f - normalizedLeft.levenshteinDistance(normalizedRight).toFloat() / maxLength.toFloat()
		if (similarity < 0.72f) {
			return 0f
		}
		return (0.7f + ((similarity - 0.72f) / 0.28f) * 0.18f).coerceIn(0.7f, 0.88f)
	}
}
