package org.skepsun.kototoro.core.os

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.room.InvalidationTracker
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.favicon.faviconUri
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.image.ThumbnailTransformation
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapNotNullToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppShortcutManager @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val coil: ImageLoader,
	private val historyRepository: HistoryRepository,
	private val mangaRepository: ContentDataRepository,
	private val settings: AppSettings,
	private val entityGraphRepository: EntityGraphRepository,
	private val workResolver: WorkResolver,
) : InvalidationTracker.Observer(
	TABLE_WORK_HISTORY,
	TABLE_ENTITY_PREFERENCES,
	TABLE_MANGA,
), SharedPreferences.OnSharedPreferenceChangeListener {

	private val iconSize by lazy {
		Size(ShortcutManagerCompat.getIconMaxWidth(context), ShortcutManagerCompat.getIconMaxHeight(context))
	}
	private var shortcutsUpdateJob: Job? = null
	@Volatile
	private var isUpdatingShortcuts = false
	@Volatile
	private var hasPendingShortcutsUpdate = false

	init {
		settings.subscribe(this)
	}

	override fun onInvalidated(tables: Set<String>) {
		if (!settings.isDynamicShortcutsEnabled) {
			return
		}
		if (isUpdatingShortcuts) {
			hasPendingShortcutsUpdate = true
			return
		}
		val prevJob = shortcutsUpdateJob
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			prevJob?.join()
			updateShortcutsImpl()
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_SHORTCUTS) {
			if (settings.isDynamicShortcutsEnabled) {
				onInvalidated(emptySet())
			} else {
				clearShortcuts()
			}
		}
	}

	suspend fun requestPinShortcut(manga: Content): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(manga), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	suspend fun requestPinShortcut(source: ContentSource): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(source), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	fun getContentShortcuts(): Set<Long> {
		val shortcuts = ShortcutManagerCompat.getShortcuts(
			context,
			ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
		)
		return shortcuts.mapNotNullToSet { it.id.toLongOrNull() }
	}

	@VisibleForTesting
	suspend fun await(): Boolean {
		return shortcutsUpdateJob?.join() != null
	}

	fun notifyContentOpened(mangaId: Long) {
		processLifecycleScope.launch(Dispatchers.Default) {
			val shortcutMangaId = mangaRepository.findDisplayContentById(mangaId, withChapters = false)?.id ?: mangaId
			ShortcutManagerCompat.reportShortcutUsed(context, shortcutMangaId.toString())
		}
	}

	fun isDynamicShortcutsAvailable(): Boolean {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
			context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity > 0
	}

	private suspend fun updateShortcutsImpl() = runCatchingCancellable {
		do {
			hasPendingShortcutsUpdate = false
			isUpdatingShortcuts = true
			val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(5)
			val shortcuts = historyRepository.getList(0, maxShortcuts)
				.filter { x -> x.title.isNotEmpty() }
				.map { buildShortcutInfo(it) }
			ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
			isUpdatingShortcuts = false
		} while (hasPendingShortcutsUpdate)
	}.onFailure {
		it.printStackTraceDebug()
	}.also {
		isUpdatingShortcuts = false
	}

	private fun clearShortcuts() {
		try {
			ShortcutManagerCompat.removeAllDynamicShortcuts(context)
		} catch (_: IllegalStateException) {
		}
	}

	private suspend fun buildShortcutInfo(manga: Content): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val entityId = workResolver.resolveByMangaId(manga.id).entityId
		val preferredLocalMangaId = entityId?.let { workResolver.selectPreferredProjection(it) }
		val resolvedId = preferredLocalMangaId ?: manga.id
		val currentManga = mangaRepository.findDisplayContentById(resolvedId, withChapters = false)
			?: mangaRepository.findPreferredLocalContentById(resolvedId, withChapters = false)
			?: mangaRepository.findContentById(resolvedId, withChapters = false)
			?: manga
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(currentManga.coverUrl)
					.size(iconSize)
					.mangaSourceExtra(currentManga.source)
					.scale(Scale.FILL)
					.transformations(ThumbnailTransformation())
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		val title = currentManga.title.ifEmpty {
			currentManga.altTitles.firstOrNull()
		}.ifNullOrEmpty {
			context.getString(R.string.unknown)
		}
		ShortcutInfoCompat.Builder(context, currentManga.id.toString())
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(
				ReaderIntent.Builder(context)
					.mangaId(currentManga.id)
					.build()
					.intent,
			).build()
	}

	private suspend fun buildShortcutInfo(source: ContentSource): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(source.faviconUri())
					.mangaSourceExtra(source)
					.size(iconSize)
					.scale(Scale.FIT)
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		val title = source.getTitle(context)
		ShortcutInfoCompat.Builder(context, source.name)
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(AppRouter.listIntent(context, source, null, null))
			.build()
	}
}
