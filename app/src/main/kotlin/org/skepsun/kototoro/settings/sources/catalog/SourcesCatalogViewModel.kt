package org.skepsun.kototoro.settings.sources.catalog

import androidx.lifecycle.viewModelScope
import androidx.room.invalidationTrackerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_SOURCES
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.mapSortedByCount
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentSource
import java.util.EnumSet
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
    private val repository: ContentSourcesRepository,
    db: MangaDatabase,
    private val settings: AppSettings,
) : BaseViewModel() {

    val onActionDone = MutableEventFlow<ReversibleAction>()

    private val externalExtensionChanges: Flow<Unit> = repository.observeExternalExtensionChanges()
        .shareIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, replay = 1)

    // Same base query as the list (disabled sources only) so chips/locales never lead to an empty result.
    private val availableSources: Flow<List<ContentSource>> = combine(
        externalExtensionChanges,
        settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
        db.invalidationTrackerFlow(TABLE_SOURCES, org.skepsun.kototoro.core.db.TABLE_JSON_SOURCES).conflate(),
    ) { _, isNsfwDisabled, _ ->
        isNsfwDisabled
    }.mapLatest { isNsfwDisabled: Boolean ->
        repository.queryAllSources(
            isDisabledOnly = true,
            excludeBroken = !settings.isShowBrokenSources,
            sortOrder = SourcesSortOrder.ALPHABETIC,
        ).filterNot { source ->
            isNsfwDisabled && source.isNsfw()
        }
    }

    val locales: StateFlow<Set<String?>> = availableSources
        .map { sources: List<ContentSource> ->
            sources.mapTo(HashSet<String?>()) { it.getLocale()?.language }.also { it.add(null) }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, setOf(null))

    private val searchQuery = MutableStateFlow<String?>(null)
    val appliedFilter = MutableStateFlow(
        SourcesCatalogFilter(
            types = emptySet(),
            locale = null,
            isNewOnly = false,
        ),
    )

    val hasNewSources = repository.observeHasNewSources()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val contentTypes: StateFlow<List<ContentType>> = availableSources
        .map { sources: List<ContentSource> -> sources.mapSortedByCount { it.getContentType() } }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val content: StateFlow<List<ListModel>> = combine(
        searchQuery.debounce { if (it.isNullOrEmpty()) 0L else 150L },
        appliedFilter,
        db.invalidationTrackerFlow(TABLE_SOURCES, org.skepsun.kototoro.core.db.TABLE_JSON_SOURCES).conflate(),
        externalExtensionChanges,
    ) { q, f, _, _ ->
        buildSourcesList(f, q)
    }.catch { e ->
        android.util.Log.e("SourcesCatalogVM", "Error in content flow", e)
        emit(listOf(errorHint()))
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    init {
        repository.clearNewSourcesBadge()
        launchJob(Dispatchers.Default) {
            locales.collect { availableLocales ->
                val selectedLocale = appliedFilter.value.locale
                if (selectedLocale != null && selectedLocale !in availableLocales) {
                    appliedFilter.value = appliedFilter.value.copy(locale = null)
                }
            }
        }
    }

    fun performSearch(query: String?) {
        searchQuery.value = query?.trim()
    }

    fun setLocale(value: String?) {
        appliedFilter.value = appliedFilter.value.copy(locale = value)
    }

    fun addSource(source: ContentSource) {
        launchJob(Dispatchers.Default) {
            val rollback = repository.setSourcesEnabled(setOf(source), true)
            onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
        }
    }

    fun setContentType(value: ContentType, isAdd: Boolean) {
        val filter = appliedFilter.value
        val types = EnumSet.noneOf(ContentType::class.java)
        types.addAll(filter.types)
        if (isAdd) {
            types.add(value)
        } else {
            types.remove(value)
        }
        appliedFilter.value = filter.copy(types = types)
    }

    fun setNewOnly(value: Boolean) {
        appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
    }

    fun setSourceType(value: SourceTypeFilter) {
        appliedFilter.value = appliedFilter.value.copy(sourceType = value)
    }

    private fun errorHint() = SourceCatalogItem.Hint(
        icon = R.drawable.ic_error_large,
        title = R.string.error_occurred,
        text = R.string.try_again,
    )

    private suspend fun buildSourcesList(filter: SourcesCatalogFilter, query: String?): List<SourceCatalogItem> {
        val allSources = repository.queryAllSources(
            isDisabledOnly = true,
            isNewOnly = filter.isNewOnly,
            excludeBroken = true,
            types = filter.types,
            query = query,
            locale = filter.locale,
            sortOrder = SourcesSortOrder.ALPHABETIC,
        )

        // Apply source type filter
        val sourceTypeIdentifier = org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier()
        val sources = when (filter.sourceType) {
            SourceTypeFilter.ALL -> allSources
            SourceTypeFilter.NATIVE -> allSources.filter { !sourceTypeIdentifier.isJsonSource(it.name) }
            SourceTypeFilter.JSON -> allSources.filter { sourceTypeIdentifier.isJsonSource(it.name) }
        }

        return if (sources.isEmpty()) {
            listOf(
                if (query == null) {
                    SourceCatalogItem.Hint(
                        icon = R.drawable.ic_empty_feed,
                        title = R.string.no_manga_sources,
                        text = R.string.no_manga_sources_catalog_text,
                    )
                } else {
                    SourceCatalogItem.Hint(
                        icon = R.drawable.ic_empty_feed,
                        title = R.string.nothing_found,
                        text = R.string.no_manga_sources_found,
                    )
                },
            )
        } else {
            sources.map {
                SourceCatalogItem.Source(source = it)
            }
        }
    }

}
