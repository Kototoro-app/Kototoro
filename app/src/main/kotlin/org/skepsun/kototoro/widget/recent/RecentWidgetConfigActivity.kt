package org.skepsun.kototoro.widget.recent

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.prefs.AppWidgetConfig
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class RecentWidgetConfigActivity : BaseComposeActivity() {

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
		config = AppWidgetConfig(this, RecentWidgetProvider::class.java, appWidgetId)
		setComposeContent {
			RecentWidgetConfigScreen(
				initialHasBackground = config.hasBackground,
				onNavigateUp = ::finishAfterTransition,
				onDone = ::saveConfiguration,
			)
		}
	}

	private fun saveConfiguration(hasBackground: Boolean) {
		config.hasBackground = hasBackground
		updateWidget()
		setResult(
			RESULT_OK,
			Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.widgetId),
		)
		finish()
	}

	private fun updateWidget() {
		val intent = Intent(this, RecentWidgetProvider::class.java)
		intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
		val ids = intArrayOf(config.widgetId)
		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
		sendBroadcast(intent)
	}
}
