package org.skepsun.kototoro.settings.sources.blacklist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class GlobalTagBlacklistActivity : BaseComposeActivity() {

	private val viewModel by viewModels<GlobalTagBlacklistViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setComposeContent {
			GlobalTagBlacklistScreen(
				state = viewModel.uiState.collectAsStateWithLifecycle().value,
				query = viewModel.searchQuery.collectAsStateWithLifecycle().value,
				onQueryChange = { viewModel.searchQuery.value = it },
				onAddQuery = viewModel::addQuery,
				onToggleTag = viewModel::toggleTag,
				onClear = viewModel::clear,
				onNavigateUp = ::finishAfterTransition,
			)
		}
	}

	companion object {
		fun newIntent(context: Context): Intent = Intent(context, GlobalTagBlacklistActivity::class.java)
	}
}
