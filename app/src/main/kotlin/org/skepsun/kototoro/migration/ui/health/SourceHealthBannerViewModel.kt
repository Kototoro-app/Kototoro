package org.skepsun.kototoro.migration.ui.health

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import javax.inject.Inject

data class HealthBannerState(val sourceCount: Int = 0, val key: String? = null)

@HiltViewModel
class SourceHealthBannerViewModel @Inject constructor(
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val _state = MutableStateFlow(HealthBannerState())
    val state: StateFlow<HealthBannerState> = _state

    init {
        refresh()
    }

    fun refresh() {
        launchJob(Dispatchers.Default) {
            val attention = sourceHealthUseCase().filter { it.status.needsAttention }
            val key = attention.map { it.source.name }.sorted().joinToString(",")
            _state.value = if (attention.isEmpty() || key == settings.dismissedHealthKey) {
                HealthBannerState()
            } else {
                HealthBannerState(attention.size, key)
            }
        }
    }

    fun dismiss() {
        settings.dismissedHealthKey = _state.value.key
        _state.value = HealthBannerState()
    }
}
