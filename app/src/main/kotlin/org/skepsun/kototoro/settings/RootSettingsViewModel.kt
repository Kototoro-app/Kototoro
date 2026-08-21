package org.skepsun.kototoro.settings

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import javax.inject.Inject

@HiltViewModel
class RootSettingsViewModel @Inject constructor(
    sourcesRepository: ContentSourcesRepository,
) : BaseViewModel() {

    val totalSourcesCount = sourcesRepository.allContentSources.size

    val enabledSourcesCount = sourcesRepository.observeEnabledSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)
}
