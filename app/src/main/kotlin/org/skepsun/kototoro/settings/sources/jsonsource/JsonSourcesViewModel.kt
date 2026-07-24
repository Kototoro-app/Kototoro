package org.skepsun.kototoro.settings.sources.jsonsource

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.jsonsource.GroupedSourceList
import org.skepsun.kototoro.core.jsonsource.GroupingStrategy
import org.skepsun.kototoro.core.jsonsource.JsonSourceImportMetadata
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.jsonsource.SourceGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import android.net.Uri

/**
 * ViewModel for managing JSON sources with grouping support.
 * 
 * Provides functionality to:
 * - Observe all JSON sources
 * - Toggle source enabled/disabled state
 * - Delete sources
 * - Test sources (execute test requests)
 * - Group sources by content type or origin type
 * - Manage group collapse/expand state
 */
@HiltViewModel
class JsonSourcesViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val jsonSourceManager: JsonSourceManager,
	private val sourceGroupManager: SourceGroupManager,
	private val mangaSourcesRepository: ContentSourcesRepository,
	private val json: Json,
	private val appSettings: AppSettings,
) : BaseViewModel() {

	val sourceTypeFilter: JsonSourceType? = savedStateHandle.get<String>(ARG_SOURCE_TYPE)
		?.let { runCatching { JsonSourceType.valueOf(it) }.getOrNull() }
	
	/**
	 * StateFlow of all JSON sources from the database.
	 * Automatically updates when sources change.
	 */
	private val allJsonSources: StateFlow<List<JsonSourceEntity>> = jsonSourceManager
		.observeAllJsonSources()
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = emptyList()
		)

	val jsonSources: StateFlow<List<JsonSourceEntity>> = allJsonSources
		.map { sources ->
			sourceTypeFilter?.let { type -> sources.filter { it.type == type } } ?: sources
		}
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = emptyList(),
		)
	
	private val parsedSources = jsonSources.map { sources ->
		sources.associate { entity ->
			entity.id to runCatching {
				parseSourceMeta(entity)
			}.getOrNull()
		}
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.Eagerly,
		initialValue = emptyMap()
	)

	val availableGroups: StateFlow<List<String>> = parsedSources.map { sourceMap ->
		sourceMap.values.filterNotNull()
			.flatMap { it.groups }
			.distinct()
			.sorted()
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = emptyList()
	)

	/**
	 * Current grouping strategy (by content or by origin).
	 */
	private val _groupingStrategy = MutableStateFlow(GroupingStrategy.BY_ORIGIN)
	val groupingStrategy: StateFlow<GroupingStrategy> = _groupingStrategy.asStateFlow()
	
	/**
	 * Map of collapsed group states.
	 * Key: SourceGroup, Value: isCollapsed
	 */
	private val _collapsedGroups = MutableStateFlow<Map<SourceGroup, Boolean>>(emptyMap())
	
	/**
	 * Current sort option.
	 */
	private val _sortOption = MutableStateFlow<SortOption>(SortOption.NAME)
	
	/**
	 * Current filter option.
	 */
	private val _filterOption = MutableStateFlow<FilterOption>(FilterOption.ALL)
	
	/**
	 * Current search query.
	 */
	private val _searchQuery = MutableStateFlow("")
	
	/**
	 * StateFlow of grouped sources.
	 * Combines all JSON sources with grouping strategy, collapsed states, sort, filter and search.
	 * Shows ALL JSON sources (both enabled and disabled) for management.
	 */
	val groupedSources: StateFlow<GroupedSourceList> = combine(
		jsonSources,
		_groupingStrategy,
		_collapsedGroups,
		_sortOption,
		combine(_filterOption, _searchQuery) { filter, query -> filter to query }
	) { jsonSourceEntities, strategy, collapsedMap, sort, (filter, searchQuery) ->
		// Apply search filter first
		val searchedEntities = if (searchQuery.isBlank()) {
			jsonSourceEntities
		} else {
			val query = searchQuery.lowercase()
			jsonSourceEntities.filter { entity ->
				entity.name.lowercase().contains(query) ||
				entity.id.lowercase().contains(query)
			}
		}
		
		// Filter sources
		val filteredEntities = when (filter) {
			is FilterOption.ALL -> searchedEntities
			is FilterOption.ENABLED -> searchedEntities.filter { it.enabled }
			is FilterOption.DISABLED -> searchedEntities.filter { !it.enabled }
			is FilterOption.INVALID -> {
				val invalidIds = _validationStates.value.filter { it.value == false }.keys
				searchedEntities.filter { invalidIds.contains(it.id) }
			}
			is FilterOption.NEED_LOGIN -> {
				searchedEntities.filter { entity ->
					parsedSources.value[entity.id]?.loginUrl?.isNotBlank() == true
				}
			}
			is FilterOption.NO_GROUP -> {
				searchedEntities.filter { entity ->
					parsedSources.value[entity.id]?.groups.isNullOrEmpty()
				}
			}
			is FilterOption.EXPLORE_ENABLED -> {
				searchedEntities.filter { entity ->
					parsedSources.value[entity.id]?.hasExplore == true
				}
			}
			is FilterOption.EXPLORE_DISABLED -> {
				searchedEntities.filter { entity ->
					parsedSources.value[entity.id]?.hasExplore == false
				}
			}
			is FilterOption.GROUP -> {
				searchedEntities.filter { entity ->
					parsedSources.value[entity.id]?.groups?.contains(filter.name) == true
				}
			}
		}
		
		// Sort sources
		val sortedEntities = when (sort) {
			SortOption.NAME -> filteredEntities.sortedBy { it.name.lowercase() }
			SortOption.ENABLED -> filteredEntities.sortedByDescending { it.enabled }
		}
		
		// Convert JSON source entities to ContentSourceInfo
		val sourceInfoList = sortedEntities.map { entity ->
			val jsonContentSource = org.skepsun.kototoro.core.jsonsource.JsonContentSource(entity)
			ContentSourceInfo(
				mangaSource = jsonContentSource,
				isEnabled = entity.enabled,
				isPinned = entity.isPinned
			)
		}
		
		// Create grouped list
		val grouped = GroupedSourceList.fromSources(
			sources = sourceInfoList,
			groupBy = strategy,
			sourceGroupManager = sourceGroupManager
		)
		
		// Apply collapsed states
		val groupsWithCollapsedState = grouped.groups.map { groupInfo ->
			val isCollapsed = collapsedMap[groupInfo.group] ?: false
			groupInfo.withCollapsed(isCollapsed)
		}
		
		GroupedSourceList(groupsWithCollapsedState).filterNonEmpty()
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = GroupedSourceList.empty()
	)
	
	private val _testResult = MutableStateFlow<TestResult?>(null)
	val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()
	private val _validationStates = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
	val validationStates: StateFlow<Map<String, Boolean?>> = _validationStates.asStateFlow()
	
	private val _validationProgress = MutableStateFlow<Pair<Int, Int>?>(null)
	val validationProgress: StateFlow<Pair<Int, Int>?> = _validationProgress.asStateFlow()
	
	private val _lastInvalidIds = MutableStateFlow<List<String>>(emptyList())
	val lastInvalidIds: StateFlow<List<String>> = _lastInvalidIds.asStateFlow()
	
	/**
	 * Toggles the enabled state of a JSON source.
	 * 
	 * @param sourceId The source identifier
	 * @param enabled Whether the source should be enabled
	 */
	fun toggleSource(sourceId: String, enabled: Boolean) {
		launchJob(Dispatchers.Default) {
			jsonSourceManager.toggleSource(sourceId, enabled)
		}
	}
	
	/**
	 * Deletes a JSON source from the database.
	 * 
	 * @param sourceId The source identifier
	 */
	fun deleteSource(sourceId: String) {
		launchJob(Dispatchers.Default) {
			jsonSourceManager.deleteSource(sourceId)
		}
	}
	
	/**
	 * Tests a JSON source by attempting to execute a test request.
	 * 
	 * This is a placeholder implementation. In a full implementation,
	 * this would:
	 * 1. Create a dynamic parser from the source configuration
	 * 2. Execute a test search or list request
	 * 3. Return success/failure result
	 * 
	 * @param sourceId The source identifier to test
	 */
	fun testSource(sourceId: String) {
		viewModelScope.launch(Dispatchers.Default) {
			_testResult.value = TestResult.Testing(sourceId)
			
			try {
				val entity = jsonSources.value.firstOrNull { it.id == sourceId }
				if (entity?.type != JsonSourceType.LEGADO) {
					_testResult.value = TestResult.Error(sourceId, "Validation is only supported for Legado sources")
					return@launch
				}
				val ok = jsonSourceManager.validateSourceBySearch(sourceId, searchKey = "我的")
				if (ok) {
					_testResult.value = TestResult.Success(sourceId, "Search test passed")
				} else {
					_testResult.value = TestResult.Error(sourceId, "Search test failed")
				}
				val updated = _validationStates.value.toMutableMap()
				updated[sourceId] = ok
				_validationStates.value = updated
			} catch (e: Exception) {
				_testResult.value = TestResult.Error(
					sourceId,
					e.message ?: "Unknown error occurred"
				)
			}
		}
	}
	
	fun batchEnable(ids: List<String>, enabled: Boolean) {
		launchJob(Dispatchers.Default) {
			jsonSourceManager.toggleSourcesBatch(ids, enabled)
		}
	}
	
	fun batchDelete(ids: List<String>) {
		launchJob(Dispatchers.Default) {
			jsonSourceManager.deleteSourcesBatch(ids)
		}
	}
	
	/**
	 * Export selected sources as JSON string.
	 * Returns a pair of (JSON string, count of exported sources).
	 */
	suspend fun exportSources(ids: List<String>): Pair<String, Int> {
		val sources = jsonSources.value.filter { ids.contains(it.id) }
		val jsonArray = org.json.JSONArray()
		sources.forEach { entity ->
			try {
				// entity.config contains the original Legado JSON
				val jsonObject = org.json.JSONObject(JsonSourceImportMetadata.strip(entity.config))
				jsonArray.put(jsonObject)
			} catch (e: Exception) {
				// Fallback to basic info if config is invalid
				val fallback = org.json.JSONObject().apply {
					put("bookSourceName", entity.name)
					put("enabled", entity.enabled)
				}
				jsonArray.put(fallback)
			}
		}
		return jsonArray.toString(2) to sources.size
	}
	
	private var validationJob: Job? = null
	
	fun batchValidate(ids: List<String>) {
		if (ids.isEmpty()) return
		
		validationJob?.cancel()
		validationJob = launchJob(Dispatchers.Default) {
			_validationProgress.value = 0 to ids.size
			val invalidIds = java.util.Collections.synchronizedList(mutableListOf<String>())
			val updated = java.util.concurrent.ConcurrentHashMap(_validationStates.value)
			
			var completedCount = 0
			val totalCount = ids.size
			
			// Use a semaphore or chunking to limit concurrency (e.g., 3 at a time)
			val semaphore = kotlinx.coroutines.sync.Semaphore(3)
			
			kotlinx.coroutines.coroutineScope {
				ids.map { id ->
					launch {
						semaphore.withPermit {
							try {
								val entity = jsonSources.value.firstOrNull { it.id == id }
								if (entity?.type == JsonSourceType.LEGADO) {
									val ok = jsonSourceManager.validateSourceBySearch(id, searchKey = "我的")
									updated[id] = ok
									if (!ok) {
										invalidIds.add(id)
									}
								} else {
									updated[id] = null
								}
							} catch (e: Exception) {
								if (e is kotlinx.coroutines.CancellationException) throw e
								updated[id] = false
								invalidIds.add(id)
							} finally {
								synchronized(this@JsonSourcesViewModel) {
									completedCount++
									_validationStates.value = updated.toMap()
									_validationProgress.value = completedCount to totalCount
								}
							}
						}
					}
				}
			}.joinAll()
			
			_lastInvalidIds.value = invalidIds.toList()
			// Keep the progress for a moment then clear
			kotlinx.coroutines.delay(1000)
			_validationProgress.value = null
			validationJob = null
		}
	}
	
	fun stopValidation() {
		validationJob?.cancel()
		validationJob = null
		_validationProgress.value = null
	}
	
	fun clearLastInvalidIds() {
		_lastInvalidIds.value = emptyList()
	}

	/**
	 * Clears the test result.
	 */
	fun clearTestResult() {
		_testResult.value = null
	}
	
	/**
	 * Toggles the collapsed state of a group.
	 * 
	 * @param group The source group to toggle
	 */
	fun toggleGroupCollapsed(group: SourceGroup) {
		val currentMap = _collapsedGroups.value
		val currentState = currentMap[group] ?: false
		_collapsedGroups.value = currentMap + (group to !currentState)
	}
	
	/**
	 * Sets the grouping strategy.
	 * 
	 * @param strategy The new grouping strategy
	 */
	fun setGroupingStrategy(strategy: GroupingStrategy) {
		_groupingStrategy.value = strategy
	}
	
	/**
	 * Collapses all groups.
	 */
	fun collapseAllGroups() {
		val allGroups = groupedSources.value.groups.map { it.group }
		_collapsedGroups.value = allGroups.associateWith { true }
	}
	
	/**
	 * Expands all groups.
	 */
	fun expandAllGroups() {
		_collapsedGroups.value = emptyMap()
	}
	
	/**
	 * Returns a list of all current JSON source IDs.
	 */
	fun getJsonSourceIds(): List<String> {
		return jsonSources.value.map { it.id }
	}
	
	/**
	 * Returns a list of all currently visible JSON source IDs (after filtering).
	 */
	fun getVisibleJsonSourceIds(): List<String> {
		return groupedSources.value.getAllSources()
			.filter { it.mangaSource is org.skepsun.kototoro.core.jsonsource.JsonContentSource }
			.map { (it.mangaSource as org.skepsun.kototoro.core.jsonsource.JsonContentSource).entity.id }
	}
	
	/**
	 * Sets the sort option.
	 */
	fun setSortOption(option: SortOption) {
		_sortOption.value = option
	}
	
	/**
	 * Sets the filter option.
	 */
	fun setFilterOption(option: FilterOption) {
		_filterOption.value = option
	}
	
	/**
	 * Sets the search query.
	 */
	fun setSearchQuery(query: String) {
		_searchQuery.value = query
	}

	private fun parseSourceMeta(entity: JsonSourceEntity): JsonSourceMeta = when (entity.type) {
		JsonSourceType.LEGADO -> {
			val source = json.decodeFromString<LegadoBookSource>(entity.config)
			JsonSourceMeta(
				loginUrl = source.loginUrl,
				groups = source.bookSourceGroup
					?.split(Regex("[,;，；]"))
					?.map { it.trim() }
					?.filter { it.isNotBlank() }
					.orEmpty(),
				hasExplore = !source.exploreUrl.isNullOrBlank(),
			)
		}
		JsonSourceType.TVBOX -> {
			val root = JSONObject(entity.config)
			val site = root.optJSONObject("site")
			val categories = site?.optJSONArray("categories")
			val groups = buildList {
				if (categories != null) {
					for (i in 0 until categories.length()) {
						val value = categories.optString(i).trim()
						if (value.isNotBlank()) add(value)
					}
				}
			}
			JsonSourceMeta(
				loginUrl = null,
				groups = groups,
				hasExplore = null,
			)
		}
		JsonSourceType.JS -> JsonSourceMeta(
			loginUrl = null,
			groups = emptyList(),
			hasExplore = null,
		)
		JsonSourceType.LNREADER -> JsonSourceMeta(
			loginUrl = null,
			groups = emptyList(),
			hasExplore = true,
		)
	}

	private fun extractTvBoxSourceLocator(rawConfig: String): String? {
		return runCatching {
			JSONObject(rawConfig)
				.optJSONObject("meta")
				?.optString("sourceLocator")
				?.trim()
				?.ifBlank { null }
		}.getOrNull()
	}

	private fun extractTvBoxSourceTitle(rawConfig: String): String? {
		return runCatching {
			JSONObject(rawConfig)
				.optJSONObject("meta")
				?.optString("sourceTitle")
				?.trim()
				?.ifBlank { null }
		}.getOrNull()
	}

	private fun buildTvBoxRepositoryTitle(locator: String): String {
		val uri = runCatching { Uri.parse(locator) }.getOrNull()
		val host = uri?.host?.trim().orEmpty()
		val tail = uri?.lastPathSegment?.trim().orEmpty()
		return when {
			host.isNotBlank() && tail.isNotBlank() -> "$host · $tail"
			host.isNotBlank() -> host
			else -> locator.substringAfterLast('/').ifBlank { locator }
		}
	}
}

private const val ARG_SOURCE_TYPE = "json_source_type"

/**
 * Result of testing a JSON source.
 */
sealed class TestResult {
	abstract val sourceId: String
	
	/**
	 * Test is in progress.
	 */
	data class Testing(override val sourceId: String) : TestResult()
	
	/**
	 * Test completed successfully.
	 */
	data class Success(override val sourceId: String, val message: String) : TestResult()
	
	/**
	 * Test failed with error.
	 */
data class Error(override val sourceId: String, val message: String) : TestResult()
}

private data class JsonSourceMeta(
	val loginUrl: String?,
	val groups: List<String>,
	val hasExplore: Boolean?,
)

enum class SortOption {
	NAME, ENABLED
}

sealed class FilterOption {
	object ALL : FilterOption()
	object ENABLED : FilterOption()
	object DISABLED : FilterOption()
	object INVALID : FilterOption()
	object NEED_LOGIN : FilterOption()
	object NO_GROUP : FilterOption()
	object EXPLORE_ENABLED : FilterOption()
	object EXPLORE_DISABLED : FilterOption()
	data class GROUP(val name: String) : FilterOption()
}
