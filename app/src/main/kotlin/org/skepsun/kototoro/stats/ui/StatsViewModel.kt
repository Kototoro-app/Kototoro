package org.skepsun.kototoro.stats.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.stats.data.StatsRepository
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.StatsDashboard
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
	private val repository: StatsRepository,
) : BaseViewModel() {

	val period = MutableStateFlow(StatsPeriod.WEEK)
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val selectedKind = MutableStateFlow(StatsContentKind.ALL)

	val dashboard = MutableStateFlow(StatsDashboard())

	init {
		launchJob(Dispatchers.Default) {
			combine(
				period,
				selectedKind,
			) { selectedPeriod, kind -> selectedPeriod to kind }
				.collectLatest { (selectedPeriod, kind) ->
					dashboard.value = withLoading {
						repository.getDashboard(selectedPeriod, emptySet(), kind)
					}
				}
		}
	}

	fun clearStats() {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearStats()
			dashboard.value = StatsDashboard()
			onActionDone.call(ReversibleAction(R.string.stats_cleared, null))
		}
	}
}
