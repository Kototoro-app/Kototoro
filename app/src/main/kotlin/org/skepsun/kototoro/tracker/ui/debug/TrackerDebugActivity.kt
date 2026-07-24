package org.skepsun.kototoro.tracker.ui.debug

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class TrackerDebugActivity : BaseComposeActivity() {

	private val viewModel by viewModels<TrackerDebugViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setComposeContent {
			val content by viewModel.content.collectAsStateWithLifecycle()
			TrackerDebugScreen(
				items = content,
				onNavigateUp = ::finish,
				onItemClick = { router.openResolvedDetails(it.manga) },
			)
		}
	}
}
