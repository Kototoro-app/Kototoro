package org.skepsun.kototoro.space.data

import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.MAX_CUSTOM_SPACES
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.primarySpaceKind

@Singleton
class DefaultSpaceCatalogRepository @Inject constructor(
    private val database: MangaDatabase,
    settings: AppSettings,
) : SpaceCatalogRepository {

    private val dao = database.getSpaceDefinitionDao()

    override val allSpaces: StateFlow<List<SpaceContext>> = settings
        .observeAsFlow(AppSettings.KEY_ENTITY_SPACE_ENABLED) { isEntitySpaceEnabled }
        .flatMapLatest { enabled ->
            if (enabled) {
                dao.observeAll().map { custom ->
                    BuiltInSpaces.contexts + custom.map { it.toContext() }
                }
            } else {
                flowOf(BuiltInSpaces.contexts)
            }
        }
        .stateIn(
            scope = processLifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = BuiltInSpaces.contexts,
        )

    override val spaces: StateFlow<List<SpaceContext>> = allSpaces
        .map { definitions -> definitions.filter(SpaceContext::enabled) }
        .stateIn(
            scope = processLifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = BuiltInSpaces.contexts,
        )

    override suspend fun create(
        title: String,
        contentTypes: Set<ContentType>,
        sourceLanguages: Set<String>,
        sourceKinds: Set<SourceType>,
    ): SpaceContext = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "Space title must not be blank" }
        require(contentTypes.isNotEmpty()) { "At least one content type is required" }
        check(dao.countActive() < MAX_CUSTOM_SPACES) { "Custom Space limit reached" }
        val now = System.currentTimeMillis()
        val space = SpaceContext(
            id = SpaceId("custom:${UUID.randomUUID()}"),
            kind = contentTypes.primarySpaceKind(),
            allowedContentTypes = contentTypes,
            title = title.trim(),
            sourceLanguages = sourceLanguages.normalizeLanguages(),
            sourceKinds = sourceKinds,
            isBuiltIn = false,
            sortKey = (allSpaces.value.maxOfOrNull(SpaceContext::sortKey) ?: 2) + 1,
        )
        dao.insert(space.toEntity(createdAt = now, updatedAt = now))
        space
    }

    override suspend fun update(space: SpaceContext) = withContext(Dispatchers.IO) {
        require(!space.isBuiltIn) { "Built-in Spaces cannot be updated" }
        require(space.title?.isNotBlank() == true) { "Space title must not be blank" }
        require(space.allowedContentTypes.isNotEmpty()) { "At least one content type is required" }
        val existing = checkNotNull(dao.find(space.id.value)) { "Unknown custom Space: ${space.id.value}" }
        dao.update(
            space.copy(
                title = space.title.trim(),
                sourceLanguages = space.sourceLanguages.normalizeLanguages(),
            ).toEntity(
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun delete(spaceId: SpaceId) = withContext(Dispatchers.IO) {
        require(spaceId.value.startsWith("custom:")) { "Built-in Spaces cannot be deleted" }
        database.withTransaction {
            dao.markDeleted(spaceId.value, System.currentTimeMillis())
            database.getSpaceSessionDao().deleteSnapshot(spaceId.value)
            database.getSpaceRoutePreferencesDao().deleteForSpace(spaceId.value)
        }
    }
}

private fun SpaceDefinitionEntity.toContext(): SpaceContext {
    val types = contentTypes.decodeNames(ContentType.entries)
    return SpaceContext(
        id = SpaceId(spaceId),
        kind = types.primarySpaceKind(),
        allowedContentTypes = types,
        title = title,
        sourceLanguages = sourceLanguages.decodeStrings().normalizeLanguages(),
        sourceKinds = sourceKinds.decodeNames(SourceType.entries),
        isBuiltIn = false,
        sortKey = sortKey,
        enabled = enabled,
    )
}

private fun SpaceContext.toEntity(createdAt: Long, updatedAt: Long) = SpaceDefinitionEntity(
    spaceId = id.value,
    title = title.orEmpty(),
    sortKey = sortKey,
    enabled = enabled,
    contentTypes = allowedContentTypes.encodeNames(),
    sourceLanguages = sourceLanguages.normalizeLanguages().sorted().joinToString(","),
    sourceKinds = sourceKinds.encodeNames(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = 0L,
)

private fun Collection<Enum<*>>.encodeNames(): String = map(Enum<*>::name).sorted().joinToString(",")

private fun String.decodeStrings(): Set<String> = split(',').mapNotNullTo(LinkedHashSet()) {
    it.trim().takeIf(String::isNotEmpty)
}

private fun <T : Enum<T>> String.decodeNames(entries: List<T>): Set<T> {
    val names = decodeStrings()
    return entries.filterTo(LinkedHashSet()) { it.name in names }
}

private fun Set<String>.normalizeLanguages(): Set<String> = mapNotNullTo(LinkedHashSet()) {
    it.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
}
