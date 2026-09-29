package org.skepsun.kototoro.migration.ui.duplicates

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class LibraryDuplicatesActivity : BaseComposeActivity() {

    private val viewModel: LibraryDuplicatesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LibraryDuplicatesScreen(
                state = state,
                onNavigateUp = ::finish,
                onOpen = { router.openDetails(it) },
                onSelectKeep = viewModel::selectKeep,
                onMerge = viewModel::merge,
                onIgnore = viewModel::ignore,
                onRequestMergeAll = viewModel::requestMergeAll,
                onConfirmMergeAll = viewModel::mergeAll,
                onDismissMergeAll = viewModel::dismissMergeAll,
            )
        }
    }
}
