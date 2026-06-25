package org.skepsun.kototoro.favourites.domain

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

data class OrganizableWork(
    val entityId: Long,
    val title: String,
    val preferredMangaId: Long?,
    val favouriteCategoryIds: Set<Long>,
    val projections: List<WorkProjection>,
)

data class WorkProjection(
    val mangaId: Long,
    val source: String,
    val title: String,
    val bindingState: EntityBindingState,
    val bindingCreatedBy: EntityBindingCreatedBy,
    val isPreferred: Boolean,
    val isFavouriteAnchor: Boolean,
)

@Reusable
class EntityOrganizeRepository @Inject constructor(
    private val db: MangaDatabase,
    private val sourcesRepository: ContentSourcesRepository,
) {

    suspend fun listFavouriteSources(): List<ContentSource> {
        val sourceCounts = listFavouriteContents()
            .groupingBy { it.manga.source }
            .eachCount()
        return sourcesRepository.getAllAvailableSourcesForListing()
            .filter { it.name in sourceCounts }
            .sortedByDescending { sourceCounts[it.name] ?: 0 }
    }

    suspend fun listFavouriteContents(sourceName: String? = null): List<FavouriteContent> {
        val contents = listFavouriteContents()
        return if (sourceName.isNullOrBlank()) {
            contents
        } else {
            contents.filter { it.manga.source == sourceName }
        }
    }

    suspend fun listFavouriteContentsByMangaIds(mangaIds: Set<Long>): List<FavouriteContent> {
        if (mangaIds.isEmpty()) {
            return emptyList()
        }
        return listFavouriteContents().filter { it.manga.id in mangaIds }
    }

    suspend fun listOrganizableWorks(): List<OrganizableWork> {
        val scope = loadWorkScope()
        if (scope.entityIds.isEmpty()) {
            return emptyList()
        }
        val entityIds = scope.entityIds.toList()
        val entitiesById = db.getEntityGraphDao()
            .findEntitiesByIds(entityIds)
            .filter { it.type == EntityType.WORK.name }
            .associateBy { it.id }
        val prefsByEntityId = db.getEntityGraphDao()
            .findEntityPrefsByIds(entityIds)
            .associateBy { it.entityId }
        val projectionMangaIds = (
            scope.favouriteAnchorMangaIdsByEntityId.values.flatten() +
                scope.bindingsByEntityId.values.flatten().mapNotNull { it.externalId.toLongOrNull() } +
                prefsByEntityId.values.mapNotNull { it.preferredLocalMangaId }
            ).toSet()
        val contentById = db.getMangaDao()
            .findWithTagsByIds(projectionMangaIds)
            .associateBy { it.manga.id }
        return entityIds.mapNotNull { entityId ->
            val entity = entitiesById[entityId] ?: return@mapNotNull null
            val preferredMangaId = prefsByEntityId[entityId]?.preferredLocalMangaId
            val favouriteAnchorIds = scope.favouriteAnchorMangaIdsByEntityId[entityId].orEmpty()
            val projections = scope.bindingsByEntityId[entityId]
                .orEmpty()
                .mapNotNull { binding ->
                    val mangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
                    val content = contentById[mangaId] ?: return@mapNotNull null
                    WorkProjection(
                        mangaId = mangaId,
                        source = content.manga.source,
                        title = content.manga.title,
                        bindingState = binding.state.toEntityBindingState(),
                        bindingCreatedBy = binding.createdBy.toEntityBindingCreatedBy(),
                        isPreferred = mangaId == preferredMangaId,
                        isFavouriteAnchor = mangaId in favouriteAnchorIds,
                    )
                }
                .sortedWith(
                    compareByDescending<WorkProjection> { it.isPreferred }
                        .thenByDescending { it.isFavouriteAnchor }
                        .thenBy { it.title.lowercase() },
                )
            OrganizableWork(
                entityId = entityId,
                title = entity.primaryName,
                preferredMangaId = preferredMangaId,
                favouriteCategoryIds = scope.favouriteCategoryIdsByEntityId[entityId].orEmpty(),
                projections = projections,
            )
        }
            .sortedBy { it.title.lowercase() }
    }

    private suspend fun listFavouriteContents(): List<FavouriteContent> {
        val entries = db.getWorkFavouritesDao().findActive()
        if (entries.isEmpty()) {
            return emptyList()
        }
        val anchorIds = entries.mapNotNull { it.anchorMangaId }.distinct()
        if (anchorIds.isEmpty()) {
            return emptyList()
        }
        val contentById = db.getMangaDao()
            .findWithTagsByIds(anchorIds)
            .associateBy { it.manga.id }
        val categoriesById = db.getFavouriteCategoriesDao()
            .findByIds(entries.map { it.categoryId }.distinct())
            .associateBy { it.categoryId.toLong() }
        return entries.mapNotNull { entry ->
            val anchorMangaId = entry.anchorMangaId ?: return@mapNotNull null
            val content = contentById[anchorMangaId] ?: return@mapNotNull null
            val category = categoriesById[entry.categoryId] ?: return@mapNotNull null
            FavouriteContent(
                favourite = FavouriteEntity(
                    mangaId = anchorMangaId,
                    categoryId = entry.categoryId,
                    sortKey = entry.sortKey,
                    isPinned = entry.isPinned,
                    createdAt = entry.createdAt,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                ),
                manga = content.manga,
                categories = listOf(category),
                tags = content.tags,
            )
        }
            .distinctBy { it.manga.id }
    }

    private suspend fun loadWorkScope(): WorkScope {
        val favourites = db.getWorkFavouritesDao().findActive()
        val entityIds = favourites.mapTo(LinkedHashSet()) { it.entityId }
        val bindings = if (entityIds.isEmpty()) {
            emptyList()
        } else {
            db.getEntityGraphDao().findActiveLocalBindingsByEntities(entityIds.toList())
        }
        val bindingsByEntityId = bindings
            .distinctBy { "${it.source}:${it.externalId}" }
            .groupBy { it.entityId }
        return WorkScope(
            entityIds = entityIds,
            bindingsByEntityId = bindingsByEntityId,
            favouriteCategoryIdsByEntityId = favourites
                .groupBy { it.entityId }
                .mapValues { (_, entries) -> entries.mapTo(LinkedHashSet()) { it.categoryId } },
            favouriteAnchorMangaIdsByEntityId = favourites
                .groupBy { it.entityId }
                .mapValues { (_, entries) -> entries.mapNotNullTo(LinkedHashSet()) { it.anchorMangaId } },
        )
    }

    private fun String.toEntityBindingState(): EntityBindingState {
        return EntityBindingState.entries.firstOrNull { it.name == this } ?: EntityBindingState.LEGACY
    }

    private fun String.toEntityBindingCreatedBy(): EntityBindingCreatedBy {
        return EntityBindingCreatedBy.entries.firstOrNull { it.name == this } ?: EntityBindingCreatedBy.LEGACY
    }

    private data class WorkScope(
        val entityIds: LinkedHashSet<Long>,
        val bindingsByEntityId: Map<Long, List<EntityBindingRecord>>,
        val favouriteCategoryIdsByEntityId: Map<Long, Set<Long>>,
        val favouriteAnchorMangaIdsByEntityId: Map<Long, Set<Long>>,
    )
}
