package org.skepsun.kototoro.suggestions.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.annotation.FloatRange
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.text.HtmlCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.parseAsHtml
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.request.ImageRequest
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.CancellationException
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaHandler
import org.skepsun.kototoro.core.model.distinctById
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.asArrayList
import org.skepsun.kototoro.core.util.ext.awaitUniqueWorkInfoByName
import org.skepsun.kototoro.core.util.ext.awaitWorkInfosByTag
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.core.util.ext.takeMostFrequent
import org.skepsun.kototoro.core.util.ext.toBitmapOrNull
import org.skepsun.kototoro.core.util.ext.trySetForeground
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.almostEquals
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.sizeOrZero
import org.skepsun.kototoro.settings.work.PeriodicWorkScheduler
import org.skepsun.kototoro.suggestions.domain.ContentSuggestion
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.domain.TagsBlacklist
import org.skepsun.kototoro.suggestions.domain.collectSourceResults
import org.skepsun.kototoro.suggestions.domain.selectBalancedBySource
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random
import androidx.appcompat.R as appcompatR

@HiltWorker
class SuggestionsWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val coil: ImageLoader,
	private val suggestionRepository: SuggestionRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val appSettings: AppSettings,
	private val captchaHandler: CaptchaHandler,
	private val workManager: WorkManager,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val sourcesRepository: ContentSourcesRepository,
) : CoroutineWorker(appContext, params) {

	private val notificationManager by lazy { NotificationManagerCompat.from(appContext) }

	override suspend fun doWork(): Result {
		trySetForeground()
		if (!appSettings.isSuggestionsEnabled) {
			suggestionRepository.clear()
			return Result.success()
		}
		val count = doWorkImpl()
		val outputData = workDataOf(DATA_COUNT to count)
		return Result.success(outputData)
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val title = applicationContext.getString(R.string.suggestions_updating)
		val channel = NotificationChannelCompat.Builder(WORKER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(title)
			.setShowBadge(true)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(true)
			.build()
		notificationManager.createNotificationChannel(channel)

		val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
			.setContentTitle(title)
			.setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.suggestionsSettingsIntent(applicationContext),
					0,
					false,
				),
			).addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				workManager.createCancelPendingIntent(id),
			)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setDefaults(0)
			.setOngoing(false)
			.setSilent(true)
			.setProgress(0, 0, true)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setForegroundServiceBehavior(
				if (TAG_ONESHOT in tags) {
					NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
				} else {
					NotificationCompat.FOREGROUND_SERVICE_DEFERRED
				},
			)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val actionIntent = PendingIntentCompat.getActivity(
				applicationContext, SETTINGS_ACTION_CODE,
				Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
					.putExtra(Settings.EXTRA_CHANNEL_ID, WORKER_CHANNEL_ID),
				0, false,
			)
			notification.addAction(
				R.drawable.ic_settings,
				applicationContext.getString(R.string.notifications_settings),
				actionIntent,
			)
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build())
		}
	}

	private suspend fun doWorkImpl(): Int {
		val seed = (
			historyRepository.getList(0, 20) +
				favouritesRepository.getLastContent(20)
			).distinctById()
		val sources = getSources()
		if (seed.isEmpty() || sources.isEmpty()) {
			return 0
		}
		val tagsBlacklist = TagsBlacklist(appSettings.suggestionsTagsBlacklist, TAG_EQ_THRESHOLD)
		val globalTagBlacklist = GlobalTagBlacklist(appSettings.globalTagBlacklist)
		val whitelistTags = appSettings.suggestionsTagsWhitelist.toList()
		val tags = (whitelistTags + seed.flatMap { it.tags.map { x -> x.title } }.takeMostFrequent(10)).distinct()

		val rawResults = collectSourceResults(
			sources = sources.filterNot {
				it.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)
			},
		) { source -> getList(source, tags, tagsBlacklist) }
		val suggestions = rawResults
			.filterNot { it in globalTagBlacklist }
			.map { manga ->
				ContentSuggestion(
					manga = manga,
					relevance = computeRelevance(manga.tags, tags),
				)
			}.toList()
			.sortedByDescending { it.relevance }
			.distinctBy { it.manga.id }
			.selectBalancedBySource(
				limit = MAX_RESULTS,
				perSourceLimit = MAX_RESULTS_PER_SOURCE,
			) { it.manga.source.name }
			.mapIndexed { index, suggestion ->
				// Room 按 relevance 排序，因此将打散后的名次编码进持久化分数。
				suggestion.copy(relevance = (MAX_RESULTS - index).toFloat() / MAX_RESULTS)
			}
		suggestionRepository.replace(suggestions)
		if (appSettings.isSuggestionsNotificationAvailable
			&& applicationContext.checkNotificationPermission(MANGA_CHANNEL_ID)
		) {
			for (i in 0..3) {
				try {
					val manga = suggestions[Random.nextInt(0, suggestions.size / 3)]
					val details = mangaRepositoryFactory.create(manga.manga.source)
						.getDetails(manga.manga)
					if (details.chapters.isNullOrEmpty()) {
						continue
					}
					if (details.rating > 0 && details.rating < RATING_MIN) {
						continue
					}
					if (details.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)) {
						continue
					}
					if (details in tagsBlacklist) {
						continue
					}
					if (details in globalTagBlacklist) {
						continue
					}
					showNotification(details)
					break
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					e.printStackTraceDebug()
				}
			}
		}
		return suggestions.size
	}

	private suspend fun getSources(): List<ContentSource> {
		if (appSettings.isSuggestionsIncludeDisabledSources) {
			val result = sourcesRepository.getAllAvailableSourcesUnfiltered().toMutableList()
			result.shuffle()
			result.sortWith(compareBy(nullsLast(LocaleComparator())) { it.getLocale() })
			return result
		} else {
			return sourcesRepository.getEnabledSources().shuffled()
		}
	}

	private suspend fun getList(
		source: ContentSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
	): List<Content> = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(source)
		val availableOrders = repository.sortOrders
		val order = preferredSortOrders.firstOrNull { it in availableOrders }
			?: availableOrders.firstOrNull()
			?: SortOrder.UPDATED
		val availableTags = repository.getFilterOptions().availableTags
		val tag = tags.firstNotNullOfOrNull { title ->
			availableTags.find { x -> x !in blacklist && x.title.almostEquals(title, TAG_EQ_THRESHOLD) }
		}
		val primaryFilter = ContentListFilter(tags = setOfNotNull(tag))
		val fallbackFilter = when (source.getContentType()) {
			ContentType.VIDEO, ContentType.HENTAI_VIDEO -> ContentListFilter.EMPTY
			else -> ContentListFilter.EMPTY
		}
		val list = repository.getList(
			offset = 0,
			order = order,
			filter = primaryFilter,
		).asArrayList().let { primaryResults ->
			if (primaryResults.isNotEmpty() || primaryFilter == fallbackFilter) {
				primaryResults
			} else {
				repository.getList(
					offset = 0,
					order = order,
					filter = fallbackFilter,
				).asArrayList()
			}
		}
		list.removeAll { content ->
			content.title.isBlank() || (content.url.isBlank() && content.publicUrl.isBlank())
		}
		if (appSettings.isSuggestionsExcludeNsfw) {
			list.removeAll { it.isNsfw() }
		}
		if (blacklist.isNotEmpty()) {
			list.removeAll { manga -> manga in blacklist }
		}
		val globalTagBlacklist = GlobalTagBlacklist(appSettings.globalTagBlacklist)
		if (!globalTagBlacklist.isEmpty) {
			list.removeAll { manga -> manga in globalTagBlacklist }
		}
		list.shuffle()
		list.take(MAX_SOURCE_RESULTS)
	}.onFailure { e ->
		if (e is CloudFlareException) {
			// 后台推荐抓取只记录 Cloudflare 状态，不自动拉起验证码处理界面。
			captchaHandler.handle(e, tryAutoResolve = false)
		}
		e.printStackTraceDebug()
	}.getOrDefault(emptyList())

	@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
	private suspend fun showNotification(manga: Content) {
		val channel = NotificationChannelCompat.Builder(MANGA_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.suggestions))
			.setDescription(applicationContext.getString(R.string.suggestions_summary))
			.setLightsEnabled(true)
			.setShowBadge(true)
			.build()
		notificationManager.createNotificationChannel(channel)

		val id = manga.url.hashCode()
		val title = applicationContext.getString(R.string.suggestion_manga, manga.title)
		val builder = NotificationCompat.Builder(applicationContext, MANGA_CHANNEL_ID)
		val tagsText = manga.tags.joinToString(", ") { it.title }
		with(builder) {
			setContentText(tagsText)
			setContentTitle(title)
			setGroup(GROUP_SUGGESTION)
			setLargeIcon(
				coil.execute(
					ImageRequest.Builder(applicationContext)
						.data(manga.coverUrl)
						.mangaSourceExtra(manga.source)
						.build(),
				).toBitmapOrNull(),
			)
			setSmallIcon(R.drawable.ic_stat_suggestion)
			val description = manga.description?.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT)?.sanitize()
			if (!description.isNullOrBlank()) {
				val style = NotificationCompat.BigTextStyle()
				style.bigText(
					buildSpannedString {
						append(tagsText)
						val chaptersCount = manga.chapters.sizeOrZero()
						appendLine()
						bold {
							append(
								applicationContext.resources.getQuantityStringSafe(
									R.plurals.chapters,
									chaptersCount,
									chaptersCount,
								),
							)
						}
						appendLine()
						append(description)
					},
				)
				style.setBigContentTitle(title)
				setStyle(style)
			}
			val intent = AppRouter.detailsIntent(applicationContext, manga)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					id,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setAutoCancel(true)
			setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
			setVisibility(if (manga.isNsfw()) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
			setShortcutId(manga.id.toString())
			priority = NotificationCompat.PRIORITY_DEFAULT

			addAction(
				R.drawable.ic_read,
				applicationContext.getString(R.string.read),
				PendingIntentCompat.getActivity(
					applicationContext,
					id + 2,
					ReaderIntent.Builder(applicationContext).manga(manga).build().intent,
					0,
					false,
				),
			)

			addAction(
				R.drawable.ic_suggestion,
				applicationContext.getString(R.string.more),
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.homeIntent(applicationContext),
					0,
					false,
				),
			)
		}
		notificationManager.notify(TAG, id, builder.build())
	}

	@FloatRange(from = 0.0, to = 1.0)
	private fun computeRelevance(mangaTags: Set<ContentTag>, allTags: List<String>): Float {
		val maxWeight = (allTags.size + allTags.size + 1 - mangaTags.size) * mangaTags.size / 2.0
		val weight = mangaTags.sumOf { tag ->
			val index = allTags.inexactIndexOf(tag.title, TAG_EQ_THRESHOLD)
			if (index < 0) 0 else allTags.size - index
		}
		return (weight / maxWeight).pow(2.0).toFloat()
	}

	private fun Iterable<String>.inexactIndexOf(element: String, threshold: Float): Int {
		forEachIndexed { i, t ->
			if (t.almostEquals(element, threshold)) {
				return i
			}
		}
		return -1
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val settings: AppSettings,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<SuggestionsWorker>(6, TimeUnit.HOURS)
				.setConstraints(createConstraints())
				.addTag(TAG)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
				.build()
			workManager
				.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
				.await()
		}

		override suspend fun unschedule() {
			workManager
				.cancelUniqueWork(TAG)
				.await()
		}

		override suspend fun isScheduled(): Boolean {
			return workManager
				.awaitUniqueWorkInfoByName(TAG)
				.any { !it.state.isFinished }
		}

		suspend fun startNow() {
			if (workManager.awaitWorkInfosByTag(TAG_ONESHOT).any { !it.state.isFinished }) {
				return
			}
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = OneTimeWorkRequestBuilder<SuggestionsWorker>()
				.setConstraints(constraints)
				.addTag(TAG_ONESHOT)
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.build()
			workManager.enqueue(request).await()
		}

		private fun createConstraints() = Constraints.Builder()
			.setRequiredNetworkType(if (settings.isSuggestionsWiFiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {

		const val TAG = "suggestions"
		const val TAG_ONESHOT = "suggestions_oneshot"
		const val DATA_COUNT = "count"
		const val WORKER_CHANNEL_ID = "suggestion_worker"
		const val MANGA_CHANNEL_ID = "suggestions"
		const val GROUP_SUGGESTION = "org.skepsun.kototoro.SUGGESTIONS"
		const val WORKER_NOTIFICATION_ID = 36
		const val MAX_RESULTS = 160
		const val MAX_SOURCE_RESULTS = 20
		const val MAX_RESULTS_PER_SOURCE = 12
		const val TAG_EQ_THRESHOLD = 0.4f
		const val RATING_MIN = 0.5f
		const val SETTINGS_ACTION_CODE = 4

		val preferredSortOrders = listOf(
			SortOrder.UPDATED,
			SortOrder.NEWEST,
			SortOrder.POPULARITY,
			SortOrder.RATING,
		)
	}

	@AssistedFactory
	interface Factory : WorkerAssistedFactory<SuggestionsWorker>
}
