package org.skepsun.kototoro.explore.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourcePresetsRepository @Inject constructor(
	private val db: MangaDatabase,
	private val sourceRuleResolver: SourceRuleResolver,
) {

	private val dao: SourcePresetsDao
		get() = db.getSourcePresetsDao()

	fun observeAll(): Flow<List<SourcePreset>> {
		return dao.observeAll().combine(sourceRuleResolver.observeResolvedSourceNames(SourceRule())) { entities, _ ->
			entities.map { it.toResolvedSourcePreset() }
		}
	}

	fun observe(id: Long): Flow<SourcePreset?> {
		return dao.observe(id).combine(sourceRuleResolver.observeResolvedSourceNames(SourceRule())) { entity, _ ->
			entity?.toResolvedSourcePreset()
		}
	}

	suspend fun getAll(): List<SourcePreset> {
		return dao.findAll().map { it.toResolvedSourcePreset() }
	}

	suspend fun getById(id: Long): SourcePreset? {
		return dao.find(id)?.toResolvedSourcePreset()
	}

	suspend fun createPreset(title: String, languages: Set<String>, sources: Set<String>): SourcePreset {
		val entity = SourcePresetEntity(
			presetId = 0,
			title = title,
			languages = languages.joinToString(","),
			sources = sources.joinToString(","),
			createdAt = System.currentTimeMillis(),
			sortKey = dao.getNextSortKey(),
			deletedAt = 0L,
		)
		val id = dao.insert(entity)
		return entity.copy(presetId = id).toSourcePreset()
	}

	suspend fun updatePreset(id: Long, title: String, languages: Set<String>) {
		dao.update(id, title, languages.joinToString(","))
	}

	suspend fun updatePresetSources(id: Long, sources: Set<String>) {
		dao.updateSources(id, sources.joinToString(","))
	}

	suspend fun deletePreset(id: Long) {
		dao.delete(id)
	}

	private fun SourcePresetEntity.toResolvedSourcePreset(): SourcePreset {
		val stored = toSourcePreset()
		return stored.copy(
			sources = if (stored.languages.isEmpty()) {
				emptySet()
			} else {
				sourceRuleResolver.resolveCurrentSourceNames(SourceRule(languages = stored.languages))
			},
		)
	}
}
