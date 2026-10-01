package org.skepsun.kototoro.local.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

/** Aggregate disk footprint of the local (downloaded) content. */
data class LocalDownloadStorageStats(
    val usedBytes: Long,
    val availableBytes: Long?,
)

/**
 * Computes the local storage footprint for the local-tab download card.
 * Directory scans can be expensive on big libraries, so the caller only
 * reports whether the download queue is idle. Committed imports/removals also
 * trigger a scan while idle, without scanning on every active download update.
 */
@HiltViewModel
class LocalDownloadStorageViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    localContentIndex: LocalContentIndex,
) : ViewModel() {

    private val _stats = MutableStateFlow<LocalDownloadStorageStats?>(null)
    val stats: StateFlow<LocalDownloadStorageStats?> = _stats.asStateFlow()

    private val isQueueIdle = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(isQueueIdle, localContentIndex.observeChanges().onStart { emit(Unit) }) { idle, _ -> idle }
                .filter { it }
                .collect {
                    // Serial collection retains a pending change if it arrives during a scan.
                    val used = runCatchingCancellable { storageManager.computeStorageSize() }.getOrNull()
                        ?: return@collect
                    val available = runCatchingCancellable { storageManager.computeAvailableSize() }.getOrNull()
                    _stats.value = LocalDownloadStorageStats(usedBytes = used, availableBytes = available)
                }
        }
    }

    fun onDownloadQueueIdleChanged(isIdle: Boolean) {
        isQueueIdle.value = isIdle
    }
}
