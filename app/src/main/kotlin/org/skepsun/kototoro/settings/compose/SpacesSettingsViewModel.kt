package org.skepsun.kototoro.settings.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.space.domain.MAX_CUSTOM_SPACES
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext

data class SpaceDefinitionsUiState(
    val spaces: List<SpaceContext> = emptyList(),
    val availableLanguages: Set<String> = emptySet(),
    val canCreate: Boolean = false,
)

@HiltViewModel
class SpacesSettingsViewModel @Inject constructor(
    private val catalogRepository: SpaceCatalogRepository,
    sourcesRepository: ContentSourcesRepository,
) : ViewModel() {

    private val allSpaces = catalogRepository.allSpaces
    private val enabledSources = sourcesRepository.observeEnabledSources()

    val uiState = combine(
        allSpaces,
        enabledSources,
    ) { spaces, sources ->
        buildUiState(spaces, sources)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildUiState(allSpaces.value, enabledSources.value),
    )

    private fun buildUiState(
        spaces: List<SpaceContext>,
        sources: List<ContentSourceInfo>,
    ) = SpaceDefinitionsUiState(
        spaces = spaces,
        availableLanguages = sources.mapNotNullTo(sortedSetOf()) {
            it.getLocale()?.language?.takeIf(String::isNotBlank)
        },
        canCreate = spaces.count { !it.isBuiltIn } < MAX_CUSTOM_SPACES,
    )

    fun save(space: SpaceContext) {
        viewModelScope.launch(Dispatchers.IO) {
            if (space.isBuiltIn) return@launch
            catalogRepository.update(space)
        }
    }

    fun create(space: SpaceContext) {
        viewModelScope.launch(Dispatchers.IO) {
            catalogRepository.create(
                title = space.title.orEmpty(),
                contentTypes = space.allowedContentTypes,
                sourceLanguages = space.sourceLanguages,
                sourceKinds = space.sourceKinds,
            )
        }
    }

    fun delete(space: SpaceContext) {
        if (space.isBuiltIn) return
        viewModelScope.launch(Dispatchers.IO) { catalogRepository.delete(space.id) }
    }

    fun move(space: SpaceContext, direction: Int) {
        if (space.isBuiltIn) return
        val custom = uiState.value.spaces.filterNot(SpaceContext::isBuiltIn)
        val index = custom.indexOfFirst { it.id == space.id }
        val other = custom.getOrNull(index + direction) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalogRepository.update(space.copy(sortKey = other.sortKey))
            catalogRepository.update(other.copy(sortKey = space.sortKey))
        }
    }
}
