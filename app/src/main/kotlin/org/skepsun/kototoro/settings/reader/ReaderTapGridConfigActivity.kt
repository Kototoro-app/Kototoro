package org.skepsun.kototoro.settings.reader

import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class ReaderTapGridConfigActivity : BaseComposeActivity() {

	private val viewModel: ReaderTapGridConfigViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setComposeContent {
			ReaderTapGridConfigScreen(
				content = viewModel.content.collectAsStateWithLifecycle().value,
				onNavigateUp = ::finishAfterTransition,
				onSetTapAction = viewModel::setTapAction,
				onReset = viewModel::reset,
				onDisableAll = viewModel::disableAll,
			)
		}
	}
}
