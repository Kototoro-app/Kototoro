package org.skepsun.kototoro.suggestions.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
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
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.awaitUniqueWorkInfoByName
import org.skepsun.kototoro.core.util.ext.awaitWorkInfosByTag
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.core.util.ext.toBitmapOrNull
import org.skepsun.kototoro.core.util.ext.trySetForeground
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.almostEquals
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.sizeOrZero
import org.skepsun.kototoro.settings.work.PeriodicWorkScheduler
import org.skepsun.kototoro.suggestions.domain.ContentSuggestion
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.domain.TagsBlacklist
import org.skepsun.kototoro.suggestions.domain.SuggestionTagMatcher
import org.skepsun.kototoro.suggestions.domain.cleanSuggestionList
import org.skepsun.kototoro.suggestions.domain.collectSourceResults
import org.skepsun.kototoro.suggestions.domain.SuggestionCandidate
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection
import org.skepsun.kototoro.suggestions.domain.planQueries
import org.skepsun.kototoro.suggestions.domain.rankSuggestions
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import org.skepsun.kototoro.suggestions.domain.SuggestionProfile
import org.skepsun.kototoro.suggestions.domain.suggestionTagKeys
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.random.Random
import androidx.appcompat.R as appcompatR

@HiltWorker
class SuggestionsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coil: ImageLoader,
    private val suggestionRepository: SuggestionRepository,
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
        if (count < 0) return Result.retry()
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
        val library = suggestionRepository.getLibraryWorks(SuggestionLimits().libraryHistory)
        val sources = getSources()
        if ((library.isEmpty() && appSettings.suggestionsTagsWhitelist.isEmpty()) || sources.isEmpty()) {
            return 0
        }
        val tagsBlacklist = TagsBlacklist(appSettings.suggestionsTagsBlacklist, TAG_EQ_THRESHOLD)
        val globalTagBlacklist = GlobalTagBlacklist(appSettings.globalTagBlacklist)
        // The taste profile, scoring and ranking are shared with the other hosts (core-domain SuggestionRules).
        val now = System.currentTimeMillis()
        val profile = SuggestionProfile.of(library, now, appSettings.suggestionsTagsWhitelist)
        val ages = suggestionRepository.getAges()
        val answered = java.util.concurrent.atomic.AtomicInteger()

        val rawResults = collectSourceResults(
            sources = sources.filterNot {
                it.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)
            },
        ) { source -> getList(source, tagsBlacklist, profile, now) { answered.incrementAndGet() } }
        // Keep the last usable cache and retry when every source failed.
        if (answered.get() == 0) return -1
        val suggestions = rankSuggestions(
            candidates = rawResults.filterNot { it.item in globalTagBlacklist },
            profile = profile,
            id = { it.id },
            source = { it.source.name },
            titles = { listOf(it.title) + it.altTitles },
            tags = { manga -> manga.tags.map { it.title } },
            now = now,
            firstSuggestedAt = { ages[it.id] },
            preferredSources = appSettings.suggestionsPreferredSources - appSettings.suggestionsExcludedSources,
            languagePriority = {
                appSettings.suggestionsLanguage.accepts(it.source.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language)
            },
        ).map { ContentSuggestion(it.item, it.relevance, it.direction, it.reason) }
        suggestionRepository.replace(suggestions)
        if (appSettings.isSuggestionsNotificationAvailable
            && applicationContext.checkNotificationPermission(MANGA_CHANNEL_ID)
            && suggestions.isNotEmpty()
        ) {
            for (i in 0..3) {
                try {
                    val manga = suggestions[Random.nextInt((suggestions.size / 3).coerceAtLeast(1))]
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
        val sources = if (appSettings.isSuggestionsIncludeDisabledSources) {
            val result = sourcesRepository.getAllAvailableSourcesForActiveTvBoxRepository().toMutableList()
            result.shuffle()
            result.sortWith(compareBy(nullsLast(LocaleComparator())) { it.getLocale() })
            result
        } else {
            sourcesRepository.getEnabledSources().shuffled()
        }
        val excludedSources = appSettings.suggestionsExcludedSources
        return sources.filterNot {
            it.name in excludedSources || (appSettings.suggestionsLanguageOnly &&
                !appSettings.suggestionsLanguage.accepts(it.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language))
        }.sortedByDescending {
            appSettings.suggestionsLanguage.accepts(it.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language)
        }
    }

    private suspend fun getList(
        source: ContentSource,
        blacklist: TagsBlacklist,
        profile: SuggestionProfile,
        seed: Long,
        onAnswered: () -> Unit,
    ): List<SuggestionCandidate<Content>> = runCatchingCancellable {
        val repository = mangaRepositoryFactory.create(source)
        val queries = profile.planQueries(
            sourceName = source.name,
            seed = seed,
            availableOrders = repository.sortOrders.map { it.name },
            availableTags = runCatchingCancellable { repository.getFilterOptions().availableTags }
                .getOrDefault(emptySet()),
            title = { it.title },
            isBlacklisted = { it in blacklist },
            matches = tagMatcher,
        )
        val globalTagBlacklist = GlobalTagBlacklist(appSettings.globalTagBlacklist)
        val excludeNsfw = appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled
        val limit = SuggestionLimits().maxSourceResults
        fun Content.isKnown() = profile.knows(id, listOf(title) + altTitles)
        queries.flatMap { query ->
            val order = SortOrder.valueOf(query.order)
            val filter = ContentListFilter(tags = setOfNotNull(query.tag))
            // One list failing must not cost the source its other one
            val first = runCatchingCancellable { repository.getList(offset = 0, order = order, filter = filter) }
                .onSuccess { onAnswered() }
                .onFailure { onSourceFailure(it) }
                .getOrDefault(emptyList())
            // A reader with a big library already has the top of any list: one page further when little is left.
            val next = if (first.isNotEmpty() && first.count { !it.isKnown() } < limit / 2) {
                val offset = if (repository.listPagingMode == ContentRepository.ListPagingMode.PAGE_INDEX) {
                    1
                } else first.size
                runCatchingCancellable { repository.getList(offset = offset, order = order, filter = filter) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            cleanSuggestionList(
                items = (first + next)
                    .mapIndexed { rank, manga -> SuggestionCandidate(manga, query.direction, query.reason, rank) }
                    .filterNot { it.item.isKnown() },
                title = { it.item.title },
                hasAddress = { it.item.url.isNotBlank() || it.item.publicUrl.isNotBlank() },
                excluded = { candidate ->
                    val manga = candidate.item
                    (excludeNsfw && manga.isNsfw()) ||
                        (blacklist.isNotEmpty() && manga in blacklist) ||
                        (!globalTagBlacklist.isEmpty && manga in globalTagBlacklist)
                },
                // Only what was asked for by taste is picked by taste; the other lists keep the source's order
                fit = { candidate ->
                    if (candidate.direction != SuggestionDirection.TASTE) {
                        0.0
                    } else {
                        profile.affinity(suggestionTagKeys(candidate.item.tags.map { it.title }))
                    }
                },
            )
        }
    }.onFailure { onSourceFailure(it) }.getOrDefault(emptyList())

    private suspend fun onSourceFailure(e: Throwable) {
        if (e is CloudFlareException) {
            // 后台推荐抓取只记录 Cloudflare 状态，不自动拉起验证码处理界面。
            captchaHandler.handle(e, tryAutoResolve = false)
        }
        e.printStackTraceDebug()
    }

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

    private val tagMatcher: SuggestionTagMatcher = { a, b -> a.almostEquals(b, TAG_EQ_THRESHOLD) }

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
        private val settings: AppSettings,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val request = PeriodicWorkRequestBuilder<SuggestionsWorker>(
                org.skepsun.kototoro.suggestions.domain.SUGGESTIONS_INTERVAL_HOURS.toLong(),
                TimeUnit.HOURS,
            )
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
        const val TAG_EQ_THRESHOLD = org.skepsun.kototoro.suggestions.domain.SUGGESTION_TAG_THRESHOLD
        const val RATING_MIN = 0.5f
        const val SETTINGS_ACTION_CODE = 4

    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<SuggestionsWorker>
}
