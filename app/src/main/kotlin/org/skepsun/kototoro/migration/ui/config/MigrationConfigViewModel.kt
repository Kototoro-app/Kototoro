package org.skepsun.kototoro.migration.ui.config

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.assignFamilies
import org.skepsun.kototoro.migration.domain.resolveContentFamily
import org.skepsun.kototoro.migration.domain.sectionOrder
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

data class FamilySources(
    val family: ContentTypeFamily,
    val available: List<ContentSource>,
    val pinned: Set<String>,
    val selected: List<String>,
    val contentCount: Int = 0,
) {
    /** [selected] as loaded sources, in search order; they always come from [available]. */
    val selectedSources: List<ContentSource>
        get() = selected.mapNotNull { name -> available.firstOrNull { it.name == name } }
}

data class MigrationConfigState(
    val count: Int = 0,
    val originSources: List<ContentSource> = emptyList(),
    val families: List<FamilySources> = emptyList(),
    val flags: Set<MigrationDataFlag> = MigrationDataFlag.ALL,
    val matchMode: MatchMode = MatchMode.FIRST_HIT,
    val extraQuery: String = "",
    val deepSearch: Boolean = false,
    val hideUnmatched: Boolean = false,
    val hideWithoutUpdates: Boolean = false,
    val duplicateCheck: Boolean = true,
    val isLoading: Boolean = true,
) {
    val canStart: Boolean get() = !isLoading && families.all { it.selected.isNotEmpty() }
}

@HiltViewModel
class MigrationConfigViewModel @Inject constructor(
    private val contentDataRepository: ContentDataRepository,
    private val database: org.skepsun.kototoro.core.db.MangaDatabase,
    private val sourcesRepository: ContentSourcesRepository,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val _state = MutableStateFlow(MigrationConfigState())
    val state: StateFlow<MigrationConfigState> = _state

    private var loadedIds: LongArray? = null

    fun load(ids: LongArray) {
        if (loadedIds?.contentEquals(ids) == true) return
        loadedIds = ids
        launchJob(Dispatchers.Default) {
            val contents = ids.toList().mapNotNull { contentDataRepository.findContentById(it, withChapters = false) }
            val originNames = contents.mapTo(LinkedHashSet()) { it.source.name }
            val enabled = sourcesRepository.getEnabledSources()
            val pinned = sourcesRepository.getPinnedSources().mapTo(HashSet()) { it.name }
            val migrationDao = database.getMigrationDao()
            // Sources the user already relies on come first: pinned, then by favourite count.
            val usage = migrationDao.findLibraryRows().groupingBy { it.source }.eachCount()
            val contentFamilies = assignFamilies(
                contents.associate {
                    it.id to resolveContentFamily(it.source.getContentType(), migrationDao.findStoredContentType(it.id))
                },
            ).values
            val familyCounts = contentFamilies.groupingBy { it }.eachCount()
            val families = familyCounts.keys.sortedBy { it.sectionOrder }.map { family ->
                val available = enabled
                    .filter {
                        (family == ContentTypeFamily.OTHER || it.getContentType().contentFamily() == family) &&
                            it.name !in originNames
                    }
                    .sortedWith(
                        compareByDescending<ContentSource> { it.name in pinned }
                            .thenByDescending { usage[it.name] ?: 0 },
                    )
                val availableNames = available.mapTo(HashSet()) { it.name }
                val saved = settings.getTargetSourceNames(family)?.filter { it in availableNames }
                val selected = saved?.takeIf { it.isNotEmpty() } ?: available.map { it.name }
                FamilySources(family, available, pinned, selected, familyCounts[family] ?: 0)
            }
            _state.value = MigrationConfigState(
                count = contents.size,
                originSources = originNames.map(sourcesRepository::resolveSource),
                families = families,
                flags = settings.dataFlags,
                matchMode = settings.matchMode,
                extraQuery = settings.extraQuery,
                deepSearch = settings.isDeepSearch,
                hideUnmatched = settings.hideUnmatched,
                hideWithoutUpdates = settings.hideWithoutUpdates,
                duplicateCheck = settings.isDuplicateCheckEnabled,
                isLoading = false,
            )
        }
    }

    /** Tick order is search order: ticking appends, unticking removes. */
    fun toggleSource(family: ContentTypeFamily, name: String) = updateFamily(family) {
        it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name)
    }

    fun selectPreset(family: ContentTypeFamily, preset: SourcePreset, visibleNames: List<String>? = null) =
        updateFamily(family) { it.withPreset(preset, visibleNames) }

    fun toggleFlag(flag: MigrationDataFlag) {
        if (flag == MigrationDataFlag.CATEGORIES) return
        _state.update { it.copy(flags = if (flag in it.flags) it.flags - flag else it.flags + flag) }
    }

    fun setMatchMode(mode: MatchMode) = _state.update { it.copy(matchMode = mode) }
    fun setExtraQuery(value: String) = _state.update { it.copy(extraQuery = value) }
    fun setDeepSearch(value: Boolean) = _state.update { it.copy(deepSearch = value) }
    fun setHideUnmatched(value: Boolean) = _state.update { it.copy(hideUnmatched = value) }
    fun setHideWithoutUpdates(value: Boolean) = _state.update { it.copy(hideWithoutUpdates = value) }
    fun setDuplicateCheck(value: Boolean) = _state.update { it.copy(duplicateCheck = value) }

    /** Persists the options; the list screen reads them from [MigrationSettings]. */
    fun save() {
        val s = _state.value
        s.families.forEach { settings.setTargetSourceNames(it.family, it.selected) }
        settings.dataFlags = s.flags
        settings.matchMode = s.matchMode
        settings.extraQuery = s.extraQuery
        settings.isDeepSearch = s.deepSearch
        settings.hideUnmatched = s.hideUnmatched
        settings.hideWithoutUpdates = s.hideWithoutUpdates
        settings.isDuplicateCheckEnabled = s.duplicateCheck
    }

    private fun updateFamily(family: ContentTypeFamily, transform: (FamilySources) -> FamilySources) =
        _state.update { s -> s.copy(families = s.families.map { if (it.family == family) transform(it) else it }) }
}

enum class SourcePreset { ALL, PINNED, ENABLED, NONE }
