package org.skepsun.kototoro.migration.ui.list

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class MigrationListActivity : BaseComposeActivity() {

    private val viewModel: MigrationListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.requestExit()) finish()
        }
        setComposeContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel.onFinished) {
                viewModel.onFinished.collect { event -> event?.consume { finish() } }
            }
            MigrationListScreen(
                state = state,
                onNavigateUp = { if (!viewModel.requestExit()) finish() },
                onFilter = viewModel::setFilter,
                onSkip = viewModel::skip,
                onSelectCandidate = viewModel::selectCandidate,
                onManualSearch = viewModel::manualSearch,
                onMigrateNow = { viewModel.migrateNow(it) },
                onOpenOriginal = { router.openDetails(it) },
                onRequestMigrate = viewModel::requestMigrate,
                onConfirmMigrate = viewModel::confirmMigrate,
                onCancelMigrate = viewModel::cancelMigrate,
                onDismissDialog = viewModel::dismissDialog,
                onAbandon = ::finish,
            )
        }
    }

    companion object {
        const val EXTRA_IDS = "ids"

        fun newIntent(context: Context, ids: LongArray): Intent =
            Intent(context, MigrationListActivity::class.java).putExtra(EXTRA_IDS, ids)
    }
}
