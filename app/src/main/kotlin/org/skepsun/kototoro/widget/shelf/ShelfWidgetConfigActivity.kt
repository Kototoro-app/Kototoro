package org.skepsun.kototoro.widget.shelf

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.prefs.AppWidgetConfig
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.observeEvent

@AndroidEntryPoint
class ShelfWidgetConfigActivity : BaseComposeActivity() {

	private val viewModel by viewModels<ShelfConfigViewModel>()

	private lateinit var config: AppWidgetConfig

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val appWidgetId = intent?.getIntExtra(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID,
		) ?: AppWidgetManager.INVALID_APPWIDGET_ID
		if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
			finishAfterTransition()
			return
		}

		config = AppWidgetConfig(this, ShelfWidgetProvider::class.java, appWidgetId)
		viewModel.checkedId = config.categoryId
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(window.decorView, null))

		setComposeContent {
			val categories = viewModel.content.collectAsStateWithLifecycle().value
			val hasBackground = rememberSaveable { mutableStateOf(config.hasBackground) }
			ShelfWidgetConfigScreen(
				categories = categories,
				hasBackground = hasBackground.value,
				onBackgroundChanged = { hasBackground.value = it },
				onCategorySelected = { viewModel.checkedId = it },
				onNavigateUp = ::finishAfterTransition,
				onDone = {
					config.categoryId = viewModel.checkedId
					config.hasBackground = hasBackground.value
					updateWidget()
					setResult(
						RESULT_OK,
						Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.widgetId),
					)
					finish()
				},
			)
		}
	}

	private fun updateWidget() {
		val intent = Intent(this, ShelfWidgetProvider::class.java)
		intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(config.widgetId))
		sendBroadcast(intent)
	}
}
