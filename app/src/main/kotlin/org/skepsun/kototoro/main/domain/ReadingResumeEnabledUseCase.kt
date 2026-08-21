package org.skepsun.kototoro.main.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.history.data.HistoryRepository
import javax.inject.Inject

class ReadingResumeEnabledUseCase @Inject constructor(
    private val networkState: NetworkState,
    private val historyRepository: HistoryRepository,
    private val settings: AppSettings,
) {

    operator fun invoke(): Flow<Boolean> = combine(
        networkState,
        settings.observeAsFlow(AppSettings.KEY_HISTORY_EXCLUDE_NSFW) { isHistoryExcludeNsfw }
            .flatMapLatest { excludeNsfw ->
                historyRepository.observeLast(excludeNsfw = excludeNsfw)
            },
    ) { isOnline, last ->
        last != null && (isOnline || last.isLocal)
    }.distinctUntilChanged()
}
