package org.skepsun.kototoro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.widget.recent.RecentWidgetProvider
import org.skepsun.kototoro.widget.shelf.ShelfWidgetProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(
	@ApplicationContext private val context: Context,
) : InvalidationTracker.Observer(
	TABLE_WORK_HISTORY,
	TABLE_ENTITY_PREFERENCES,
	TABLE_WORK_FAVOURITES,
) {

	override fun onInvalidated(tables: Set<String>) {
		if (TABLE_WORK_HISTORY in tables || TABLE_ENTITY_PREFERENCES in tables) {
			updateWidgets(RecentWidgetProvider::class.java)
		}
		if (TABLE_WORK_FAVOURITES in tables) {
			updateWidgets(ShelfWidgetProvider::class.java)
		}
	}

	private fun updateWidgets(cls: Class<*>) {
		val intent = Intent(context, cls)
		intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
		val ids = (AppWidgetManager.getInstance(context) ?: return)
			.getAppWidgetIds(ComponentName(context, cls))
		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
		context.sendBroadcast(intent)
	}
}
