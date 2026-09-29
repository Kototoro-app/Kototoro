package org.skepsun.kototoro.migration.ui.sources

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class MigrationSourcesActivity : BaseComposeActivity() {

    private val viewModel: MigrationSourcesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MigrationSourcesScreen(
                state = state,
                onNavigateUp = ::finish,
                onMigrate = { ids -> router.openMigration(ids) },
                onEnable = viewModel::enable,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
