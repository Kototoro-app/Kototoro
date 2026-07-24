package org.skepsun.kototoro.widget.shelf

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.appwidget.provideContent
import androidx.glance.unit.ColorProvider
import coil3.ImageLoader
import coil3.executeBlocking
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.RoundedCornersTransformation
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.prefs.AppWidgetConfig
import org.skepsun.kototoro.core.ui.image.TrimTransformation
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.parsers.model.Content

class ShelfWidgetProvider : GlanceAppWidgetReceiver() {

	override val glanceAppWidget: GlanceAppWidget = ShelfGlanceWidget

	override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
		super.onRestored(context, oldWidgetIds, newWidgetIds)
		copyConfigs(context, oldWidgetIds, newWidgetIds, ShelfWidgetProvider::class.java)
	}
}

private fun copyConfigs(
	context: Context,
	oldWidgetIds: IntArray,
	newWidgetIds: IntArray,
	providerClass: Class<out AppWidgetProvider>,
) {
	if (oldWidgetIds.size != newWidgetIds.size) return
	for (index in oldWidgetIds.indices) {
		val oldConfig = AppWidgetConfig(context, providerClass, oldWidgetIds[index])
		val newConfig = AppWidgetConfig(context, providerClass, newWidgetIds[index])
		newConfig.copyFrom(oldConfig)
		oldConfig.clear()
	}
}

private object ShelfGlanceWidget : GlanceAppWidget() {

	override suspend fun provideGlance(context: Context, id: GlanceId) {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val entryPoint = EntryPointAccessors.fromApplication<BaseApp.BaseAppEntryPoint>(context.applicationContext)
		val config = AppWidgetConfig(context, ShelfWidgetProvider::class.java, appWidgetId)
		val items = loadItems(
			context = context,
			favouritesRepository = entryPoint.favouritesRepository(),
			imageLoader = entryPoint.imageLoader(),
			settings = entryPoint.settings(),
			categoryId = config.categoryId,
		)
		provideContent {
			ShelfWidgetContent(
				items = items,
				hasBackground = config.hasBackground,
				emptyText = context.getString(R.string.you_have_not_favourites_yet),
			)
		}
	}

	override suspend fun onDelete(context: Context, glanceId: GlanceId) {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
		AppWidgetConfig(context, ShelfWidgetProvider::class.java, appWidgetId).clear()
		super.onDelete(context, glanceId)
	}

	private suspend fun loadItems(
		context: Context,
		favouritesRepository: FavouritesRepository,
		imageLoader: ImageLoader,
		settings: org.skepsun.kototoro.core.prefs.AppSettings,
		categoryId: Long,
	): List<ShelfWidgetItem> = withContext(Dispatchers.IO) {
		if (!settings.appPassword.isNullOrEmpty()) {
			return@withContext emptyList()
		}
		val content = runCatching {
			if (categoryId == 0L) {
				favouritesRepository.getAllContent()
			} else {
				favouritesRepository.getContent(categoryId)
			}
		}.getOrDefault(emptyList())
		content.map { item ->
			ShelfWidgetItem(
				title = item.title,
				cover = loadCover(context, imageLoader, item),
				readerIntent = ReaderIntent.Builder(context)
					.manga(item)
					.build()
					.intent,
			)
		}
	}

	private fun loadCover(context: Context, imageLoader: ImageLoader, content: Content): Bitmap? = runCatching {
		val size = Size(
			context.resources.getDimensionPixelSize(R.dimen.widget_cover_width),
			context.resources.getDimensionPixelSize(R.dimen.widget_cover_height),
		)
		val transformation = RoundedCornersTransformation(
			context.resources.getDimension(R.dimen.appwidget_corner_radius_inner),
		)
		imageLoader.executeBlocking(
			ImageRequest.Builder(context)
				.data(content.coverUrl)
				.size(size)
				.mangaExtra(content)
				.transformations(transformation, TrimTransformation())
				.build(),
		).getDrawableOrThrow().toBitmap()
	}.getOrNull()
}

private data class ShelfWidgetItem(
	val title: String,
	val cover: Bitmap?,
	val readerIntent: Intent,
)

@Composable
private fun ShelfWidgetContent(
	items: List<ShelfWidgetItem>,
	hasBackground: Boolean,
	emptyText: String,
) {
	val rootModifier = if (hasBackground) {
		GlanceModifier
			.fillMaxSize()
			.background(ImageProvider(R.drawable.bg_appwidget_root))
			.cornerRadius(R.dimen.appwidget_corner_radius_background)
			.padding(4.dp)
	} else {
		GlanceModifier
			.fillMaxSize()
			.padding(4.dp)
	}
	if (items.isEmpty()) {
		Box(
			modifier = rootModifier,
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = emptyText,
				style = TextStyle(
					color = ColorProvider(Color.Black),
				),
			)
		}
	} else {
		val gridCells = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			GridCells.Adaptive(92.dp)
		} else {
			GridCells.Fixed(2)
		}
		LazyVerticalGrid(
			gridCells = gridCells,
			modifier = rootModifier,
		) {
			items(items) { item ->
				ShelfCard(item)
			}
		}
	}
}

@Composable
private fun ShelfCard(item: ShelfWidgetItem) {
	Column(
		modifier = GlanceModifier
			.padding(4.dp)
			.background(ImageProvider(R.drawable.bg_appwidget_card))
			.cornerRadius(R.dimen.appwidget_corner_radius_inner)
			.clickable(actionStartActivity(item.readerIntent)),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Image(
			provider = item.cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.ic_placeholder),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = GlanceModifier
				.fillMaxWidth()
				.height(116.dp),
		)
		Text(
			text = item.title,
			maxLines = 2,
			style = TextStyle(
				color = ColorProvider(Color.Black),
			),
			modifier = GlanceModifier
				.fillMaxWidth()
				.padding(horizontal = 6.dp, vertical = 4.dp),
		)
	}
}

@Preview
@Composable
private fun ShelfWidgetContentPreview() {
	ShelfWidgetContent(items = emptyList(), hasBackground = true, emptyText = "No favourites")
}
