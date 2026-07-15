package org.skepsun.kototoro.remotelist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.explore.data.ContentSourcesRepository

private const val SourceResolutionTimeoutMillis = 5_000L

@HiltViewModel
class ContentListSourceGateViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val sourcesRepository: ContentSourcesRepository,
) : ViewModel() {
	private val sourceName = savedStateHandle.get<String>(AppRouter.KEY_SOURCE)
		?: savedStateHandle.get<String>("sourceName")

	val isResolutionReady: StateFlow<Boolean> = flow {
		if (sourceName.isNullOrBlank() || sourcesRepository.isSourceAvailable(sourceName)) {
			emit(true)
			return@flow
		}
		emit(false)
		withTimeoutOrNull(SourceResolutionTimeoutMillis) {
			sourcesRepository.observeEnabledSources().first {
				sourcesRepository.isSourceAvailable(sourceName)
			}
		}
		emit(true)
	}.flowOn(Dispatchers.Default).stateIn(
		scope = viewModelScope,
		started = SharingStarted.Eagerly,
		initialValue = false,
	)
}
