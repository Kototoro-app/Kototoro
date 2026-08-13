package org.skepsun.kototoro.stats.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class StatsActivity : BaseComposeActivity() {

    private val viewModel: StatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val period by viewModel.period.collectAsStateWithLifecycle()
            val kind by viewModel.selectedKind.collectAsStateWithLifecycle()
            val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
            val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

            StatsScreen(
                period = period,
                selectedKind = kind,
                dashboard = dashboard,
                isLoading = isLoading,
                onNavigateUp = ::finish,
                onPeriodSelected = { viewModel.period.value = it },
                onKindSelected = { viewModel.selectedKind.value = it },
                onClearStats = viewModel::clearStats,
                onContentClick = { router.showStatisticSheet(it) },
            )
        }
    }
}
