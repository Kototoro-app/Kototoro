package org.skepsun.kototoro.widget.shelf

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.appwidget.provideContent
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.prefs.AppWidgetConfig
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.widget.WidgetEmptyState
import org.skepsun.kototoro.widget.WidgetHeader
import org.skepsun.kototoro.widget.WidgetPrimaryTextColor
import org.skepsun.kototoro.widget.widgetRootModifier

private const val WIDGET_ITEM_LIMIT = 10
private const val COVER_CACHE_READ_TIMEOUT_MILLIS = 500L

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
	override val sizeMode: SizeMode = SizeMode.Exact

	override suspend fun provideGlance(context: Context, id: GlanceId) {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val config = AppWidgetConfig(context, ShelfWidgetProvider::class.java, appWidgetId)
		val items = runCatchingCancellable {
			val entryPoint = EntryPointAccessors.fromApplication<BaseApp.BaseAppEntryPoint>(context.applicationContext)
			loadItems(
				context = context,
				favouritesRepository = entryPoint.favouritesRepository(),
				imageLoader = entryPoint.imageLoader(),
				settings = entryPoint.settings(),
				categoryId = config.categoryId,
			)
		}.getOrDefault(emptyList())
		provideContent {
			ShelfWidgetContent(
				items = items,
				hasBackground = config.hasBackground,
				title = context.getString(R.string.manga_shelf),
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
		val content = runCatchingCancellable {
			if (categoryId == 0L) {
				favouritesRepository.getLastContent(WIDGET_ITEM_LIMIT)
			} else {
				favouritesRepository.getContent(categoryId).take(WIDGET_ITEM_LIMIT)
			}
		}.getOrDefault(emptyList())
		coroutineScope {
			content.map { item ->
				async {
					runCatchingCancellable {
						ShelfWidgetItem(
							id = item.id,
							title = item.title,
							cover = loadCover(context, imageLoader, item),
							readerIntent = ReaderIntent.Builder(context)
								.manga(item)
								.build()
								.intent,
						)
					}.getOrNull()
				}
			}.awaitAll().filterNotNull()
		}
	}

	private suspend fun loadCover(context: Context, imageLoader: ImageLoader, content: Content): Bitmap? {
		val coverUrl = content.coverUrl?.takeIf { it.isNotBlank() }
			?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
		val cacheKey = contentCoverCacheKey(content, coverUrl) ?: return null
		return withTimeoutOrNull(COVER_CACHE_READ_TIMEOUT_MILLIS) {
			runCatchingCancellable {
				imageLoader.execute(
					ImageRequest.Builder(context)
						.data(coverUrl)
						.memoryCacheKey(cacheKey)
						.diskCacheKey(cacheKey)
						.mangaExtra(content)
						.diskCachePolicy(CachePolicy.READ_ONLY)
						.networkCachePolicy(CachePolicy.DISABLED)
						.build(),
				).getDrawableOrThrow().toBitmap()
			}.getOrNull()
		}
	}
}

private data class ShelfWidgetItem(
	val id: Long,
	val title: String,
	val cover: Bitmap?,
	val readerIntent: Intent,
)

@Composable
private fun ShelfWidgetContent(
	items: List<ShelfWidgetItem>,
	hasBackground: Boolean,
	title: String,
	emptyText: String,
) {
	val rootModifier = widgetRootModifier(hasBackground)
	if (items.isEmpty()) {
		WidgetEmptyState(text = emptyText, modifier = rootModifier)
	} else {
		val size = LocalSize.current
		val gridCells = when {
			size.width < 180.dp -> GridCells.Fixed(1)
			size.width < 340.dp -> GridCells.Fixed(2)
			else -> GridCells.Fixed(3)
		}
		val showHeader = hasBackground && size.height >= 160.dp
		Column(modifier = rootModifier) {
			if (showHeader) {
				WidgetHeader(title = title, itemCount = items.size)
			}
			LazyVerticalGrid(
				gridCells = gridCells,
				modifier = GlanceModifier.defaultWeight(),
			) {
				items(
					items = items,
				) { item ->
					ShelfCard(item)
				}
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
			contentDescription = item.title,
			contentScale = ContentScale.Crop,
			modifier = GlanceModifier
				.fillMaxWidth()
				.height(116.dp),
		)
		Text(
			text = item.title,
			maxLines = 2,
			style = TextStyle(
				color = WidgetPrimaryTextColor,
				fontSize = 13.sp,
				fontWeight = FontWeight.Medium,
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
	ShelfWidgetContent(items = emptyList(), hasBackground = true, title = "Shelf", emptyText = "No favourites")
}
