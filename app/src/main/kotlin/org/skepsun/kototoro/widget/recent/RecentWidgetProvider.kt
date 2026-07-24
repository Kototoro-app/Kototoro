package org.skepsun.kototoro.widget.recent

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.model.Content

class RecentWidgetProvider : GlanceAppWidgetReceiver() {

	override val glanceAppWidget: GlanceAppWidget = RecentGlanceWidget

	override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
		super.onRestored(context, oldWidgetIds, newWidgetIds)
		copyConfigs(context, oldWidgetIds, newWidgetIds, RecentWidgetProvider::class.java)
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

private object RecentGlanceWidget : GlanceAppWidget() {

	override suspend fun provideGlance(context: Context, id: GlanceId) {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val entryPoint = EntryPointAccessors.fromApplication<BaseApp.BaseAppEntryPoint>(context.applicationContext)
		val config = AppWidgetConfig(context, RecentWidgetProvider::class.java, appWidgetId)
		val items = loadItems(context, entryPoint.historyRepository(), entryPoint.imageLoader(), entryPoint.settings())
		provideContent {
			RecentWidgetContent(
				items = items,
				hasBackground = config.hasBackground,
				emptyText = context.getString(R.string.history_is_empty),
			)
		}
	}

	override suspend fun onDelete(context: Context, glanceId: GlanceId) {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
		AppWidgetConfig(context, RecentWidgetProvider::class.java, appWidgetId).clear()
		super.onDelete(context, glanceId)
	}

	private suspend fun loadItems(
		context: Context,
		historyRepository: org.skepsun.kototoro.history.data.HistoryRepository,
		imageLoader: ImageLoader,
		settings: org.skepsun.kototoro.core.prefs.AppSettings,
	): List<RecentWidgetItem> = withContext(Dispatchers.IO) {
		if (!settings.appPassword.isNullOrEmpty()) {
			return@withContext emptyList()
		}
		runCatching { historyRepository.getList(0, 10) }
			.getOrDefault(emptyList())
			.map { content ->
				RecentWidgetItem(
					cover = loadCover(context, imageLoader, content),
					readerIntent = ReaderIntent.Builder(context)
						.manga(content)
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
				.transformations(transformation)
				.build(),
		).getDrawableOrThrow().toBitmap()
	}.getOrNull()
}

private data class RecentWidgetItem(
	val cover: Bitmap?,
	val readerIntent: Intent,
)

@Composable
private fun RecentWidgetContent(
	items: List<RecentWidgetItem>,
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
		LazyColumn(modifier = rootModifier) {
			items(items) { item ->
				Image(
					provider = item.cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.ic_placeholder),
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = GlanceModifier
						.fillMaxWidth()
						.height(116.dp)
						.clickable(actionStartActivity(item.readerIntent)),
				)
			}
		}
	}
}

@Preview
@Composable
private fun RecentWidgetContentPreview() {
	RecentWidgetContent(items = emptyList(), hasBackground = true, emptyText = "No history")
}
