package org.skepsun.kototoro.explore.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.isUnresolved
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentSourceResolutionPipeline
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches content items for a single [DiscoveryFeedRow].
 */
fun interface DiscoveryFeedRowFetcher {
    suspend fun fetch(row: DiscoveryFeedRow): List<Content>
}

class DiscoveryFeedSourceNotFoundException(
    val sourceName: String,
) : IllegalStateException("Source not found: $sourceName")

class DiscoveryFeedSavedFilterNotFoundException(
    val sourceName: String,
    val savedFilterId: Int,
) : NoSuchElementException("Saved filter #$savedFilterId not found for $sourceName")

@Singleton
class DefaultDiscoveryFeedRowFetcher internal constructor(
    private val sourceResolutionPipeline: ContentSourceResolutionPipeline,
    private val contentRepositoryFactory: ContentRepository.Factory,
    private val savedFiltersRepository: SavedFiltersRepository,
    private val settings: AppSettings,
    private val ioDispatcher: CoroutineDispatcher,
) : DiscoveryFeedRowFetcher {

    @Inject
    constructor(
        sourceResolutionPipeline: ContentSourceResolutionPipeline,
        contentRepositoryFactory: ContentRepository.Factory,
        savedFiltersRepository: SavedFiltersRepository,
        settings: AppSettings,
    ) : this(
        sourceResolutionPipeline = sourceResolutionPipeline,
        contentRepositoryFactory = contentRepositoryFactory,
        savedFiltersRepository = savedFiltersRepository,
        settings = settings,
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun fetch(row: DiscoveryFeedRow): List<Content> = withContext(ioDispatcher) {
        val source = sourceResolutionPipeline.resolveByName(row.sourceName)
        if (source.isUnresolved) {
            throw DiscoveryFeedSourceNotFoundException(row.sourceName)
        }
        val nsfwDisabled = settings.isNsfwContentDisabled
        if (nsfwDisabled && source.isNsfw()) {
            return@withContext emptyList()
        }

        val repository = contentRepositoryFactory.create(source)
        val (sortOrder, filter) = resolveRequest(repository, row)
        val items = repository.getList(
            offset = 0,
            order = sortOrder,
            filter = filter,
        )
        postFilter(items, nsfwDisabled)
    }

    private suspend fun resolveRequest(
        repository: ContentRepository,
        row: DiscoveryFeedRow,
    ): Pair<SortOrder, ContentListFilter> {
        val query = row.normalizedQuery
        val savedFilterId = row.savedFilterId
        if (savedFilterId != null) {
            val savedFilter = savedFiltersRepository.find(repository.source, savedFilterId)
            if (savedFilter != null) {
                val baseFilter = savedFilter.filter
                val effectiveFilter = if (
                    query != null &&
                    baseFilter.query.isNullOrBlank() &&
                    repository.filterCapabilities.isSearchWithFiltersSupported
                ) {
                    baseFilter.copy(query = query)
                } else {
                    baseFilter
                }
                return repository.defaultSortOrder to effectiveFilter
            }
            if (query != null) {
                return preferredQuerySortOrder(repository) to ContentListFilter(query = query)
            }
            throw DiscoveryFeedSavedFilterNotFoundException(row.sourceName, savedFilterId)
        }
        if (query != null) {
            return preferredQuerySortOrder(repository) to ContentListFilter(query = query)
        }
        return repository.defaultSortOrder to ContentListFilter.EMPTY
    }

    private fun preferredQuerySortOrder(repository: ContentRepository): SortOrder {
        return if (SortOrder.RELEVANCE in repository.sortOrders) {
            SortOrder.RELEVANCE
        } else {
            repository.defaultSortOrder
        }
    }

    private fun postFilter(items: List<Content>, nsfwDisabled: Boolean): List<Content> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
        val seenIds = HashSet<Long>(items.size)
        return items.filter { item ->
            if (!seenIds.add(item.id)) return@filter false
            if (nsfwDisabled && item.isNsfw()) return@filter false
            if (!globalTagBlacklist.isEmpty && item in globalTagBlacklist) return@filter false
            true
        }
    }
}
