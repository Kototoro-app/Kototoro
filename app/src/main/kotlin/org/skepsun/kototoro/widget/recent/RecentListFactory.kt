package org.skepsun.kototoro.widget.recent

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.executeBlocking
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.RoundedCornersTransformation
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.replaceWith
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

class RecentListFactory(
	private val context: Context,
	private val historyRepository: HistoryRepository,
	private val coilLazy: Lazy<ImageLoader>,
	private val settings: AppSettings,
) : RemoteViewsService.RemoteViewsFactory {

	private val dataSet = ArrayList<Content>()
	private val transformation = RoundedCornersTransformation(
		context.resources.getDimension(R.dimen.appwidget_corner_radius_inner),
	)
	private val coverSize = Size(
		context.resources.getDimensionPixelSize(R.dimen.widget_cover_width),
		context.resources.getDimensionPixelSize(R.dimen.widget_cover_height),
	)

	override fun onCreate() = Unit

	override fun getLoadingView() = null

	override fun getItemId(position: Int) = dataSet.getOrNull(position)?.id ?: 0L

	override fun onDataSetChanged() {
		val data = if (settings.appPassword.isNullOrEmpty()) {
			runBlocking { historyRepository.getList(0, 10) }
		} else {
			emptyList()
		}
		dataSet.replaceWith(data)
	}

	override fun hasStableIds() = true

	override fun getViewAt(position: Int): RemoteViews {
		val views = RemoteViews(context.packageName, R.layout.item_recent)
		val item = dataSet.getOrNull(position) ?: return views
		runCatchingCancellable {
			coilLazy.get().executeBlocking(
				ImageRequest.Builder(context)
					.data(item.coverUrl)
					.size(coverSize)
					.mangaExtra(item)
					.transformations(transformation)
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.onSuccess { cover ->
			views.setImageViewBitmap(R.id.imageView_cover, cover)
		}.onFailure {
			views.setImageViewResource(R.id.imageView_cover, R.drawable.ic_placeholder)
		}
		val intent = Intent()
		intent.putExtra(AppRouter.KEY_ID, item.id)
		intent.putExtra(AppRouter.KEY_MANGA, ParcelableContent(item))
		views.setOnClickFillInIntent(R.id.imageView_cover, intent)
		return views
	}

	override fun getCount() = dataSet.size

	override fun getViewTypeCount() = 1

	override fun onDestroy() = Unit
}
