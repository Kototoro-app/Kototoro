package org.skepsun.kototoro.stats.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import org.skepsun.kototoro.stats.ui.views.PieChartView
import org.skepsun.kototoro.parsers.model.Content

@AndroidEntryPoint
class StatsActivity : BaseComposeActivity() {

    private val viewModel: StatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val period by viewModel.period.collectAsStateWithLifecycle()
            val categories by viewModel.favoriteCategories.collectAsStateWithLifecycle(emptyList())
            val selectedCategories by viewModel.selectedCategories.collectAsStateWithLifecycle()
            val stats by viewModel.readingStats.collectAsStateWithLifecycle()
            val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

            StatsScreen(
                period = period,
                categories = categories,
                selectedCategoryIds = selectedCategories,
                stats = stats,
                isLoading = isLoading,
                onNavigateUp = ::finish,
                onPeriodSelected = { viewModel.period.value = it },
                onCategoryChecked = viewModel::setCategoryChecked,
                onClearStats = viewModel::clearStats,
                onContentClick = { router.showStatisticSheet(it) },
            )
        }
    }
}
