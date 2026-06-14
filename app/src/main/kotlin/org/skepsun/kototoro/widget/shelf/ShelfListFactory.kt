package org.skepsun.kototoro.widget.shelf

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
import org.skepsun.kototoro.core.prefs.AppWidgetConfig
import org.skepsun.kototoro.core.ui.image.TrimTransformation
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.replaceWith

class ShelfListFactory(
	private val context: Context,
	private val favouritesRepository: FavouritesRepository,
	private val coilLazy: Lazy<ImageLoader>,
	private val settings: AppSettings,
	widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

	private val dataSet = ArrayList<Content>()
	private val config = AppWidgetConfig(context, ShelfWidgetProvider::class.java, widgetId)
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
			runBlocking {
				val category = config.categoryId
				if (category == 0L) {
					favouritesRepository.getAllContent()
				} else {
					favouritesRepository.getContent(category)
				}
			}
		} else {
			emptyList()
		}
		dataSet.replaceWith(data)
	}

	override fun hasStableIds() = true

	override fun getViewAt(position: Int): RemoteViews {
		val views = RemoteViews(context.packageName, R.layout.item_shelf)
		val item = dataSet.getOrNull(position) ?: return views
		views.setTextViewText(R.id.textView_title, item.title)
		runCatching {
			coilLazy.get().executeBlocking(
				ImageRequest.Builder(context)
					.data(item.coverUrl)
					.size(coverSize)
					.mangaExtra(item)
					.transformations(transformation, TrimTransformation())
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
		views.setOnClickFillInIntent(R.id.rootLayout, intent)
		return views
	}

	override fun getCount() = dataSet.size

	override fun getViewTypeCount() = 1

	override fun onDestroy() = Unit
}
