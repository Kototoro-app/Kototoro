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
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunitySuggestionsRepository
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunityRepository
import org.skepsun.kototoro.community.domain.CommunityRecommendations
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaHandler
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getTitle
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
import org.skepsun.kototoro.suggestions.domain.PopularSourceProbe
import org.skepsun.kototoro.suggestions.domain.PopularSources
import org.skepsun.kototoro.suggestions.domain.SuggestionCursors
import org.skepsun.kototoro.suggestions.domain.SuggestionOrigins
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionQuality
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
import org.skepsun.kototoro.suggestions.domain.LibraryWork
import org.skepsun.kototoro.suggestions.domain.suggestionRotationSeed
import org.skepsun.kototoro.suggestions.domain.suggestionTagKeys
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.flow.first
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
    private val communitySuggestions: CommunitySuggestionsRepository,
) : CoroutineWorker(appContext, params) {

    private val notificationManager by lazy { NotificationManagerCompat.from(appContext) }

    override suspend fun doWork(): Result {
        trySetForeground()
        if (!appSettings.isSuggestionsEnabled) {
            suggestionRepository.clear()
            return Result.success()
        }
        val count = doWorkImpl(append = inputData.getBoolean(DATA_APPEND, false))
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

    /** [append] loads further pages of every source and adds them below the stored list instead of replacing it. */
    private suspend fun doWorkImpl(append: Boolean): Int {
        if (append && suggestionRepository.count() >= MAX_STORED) return 0
        val library = suggestionRepository.getLibraryWorks(SuggestionLimits().libraryHistory)
        val now = System.currentTimeMillis()
        val refreshAt = inputData.getLong(DATA_REFRESH_AT, 0L)
        val rotationSeed = suggestionRotationSeed(refreshAt.takeIf { it != 0L } ?: now, refreshAt)
        val seed = if (append) appSettings.suggestionsRotationSeed.takeIf { it != 0L } ?: rotationSeed else rotationSeed
        val sources = getSources(seed)
        // An empty library still gets the sources' popular and new lists; there is just no taste to ask for yet.
        if (sources.isEmpty()) return 0
        val tagsBlacklist = TagsBlacklist(appSettings.suggestionsTagsBlacklist, TAG_EQ_THRESHOLD)
        val globalTagBlacklist = GlobalTagBlacklist(appSettings.globalTagBlacklist)
        // The taste profile, scoring and ranking are shared with the other hosts (core-domain SuggestionRules).
        val profile = SuggestionProfile.of(library, now, appSettings.suggestionsTagsWhitelist)
        val ages = suggestionRepository.getAges()
        val answered = java.util.concurrent.atomic.AtomicInteger()
        val answeredSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val cursors = if (append) SuggestionCursors.parse(appSettings.suggestionsCursors) else null
        val stored = if (append) suggestionRepository.knownIds() else emptySet()

        val rawResults = collectSourceResults(
            sources = sources.filterNot {
                it.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)
            },
        ) { source ->
            getList(source, tagsBlacklist, profile, seed, cursors?.get(source.name) ?: cursors?.let { SuggestionCursors.START }, stored) {
                answered.incrementAndGet()
                answeredSources += source.name
            }
        }
        // Keep the last usable cache and retry when every source failed.
        if (answered.get() == 0) return if (append) 0 else -1
        val candidates = rawResults.filterNot { it.item in globalTagBlacklist || it.item.id in stored }
        val preferredSources = PopularSources.preferred(
            popularFirst = appSettings.isSuggestionsPopularSourcesFirst,
            picked = appSettings.suggestionsPreferredSources,
            excluded = appSettings.suggestionsExcludedSources,
            sources = sources.map { PopularSourceProbe(it.name, it.getTitle(applicationContext)) },
            favourites = if (appSettings.isSuggestionsFavouriteSourcesFirst) {
                PopularSources.favouriteSources(library)
            } else emptySet(),
        )
        val community = if (!append && appSettings.isCommunityEnabled) {
            communityCandidates(library, sources, preferredSources, candidates.map { it.item }) { item ->
                !item.isNsfw() && item !in tagsBlacklist && item !in globalTagBlacklist &&
                    !profile.knows(item.id, listOf(item.title) + item.altTitles)
            }
        } else emptyList()
        val allCandidates = candidates + community
        val ratings = communitySuggestions.cachedRatings(allCandidates.map { it.item })
        val suggestions = rankSuggestions(
            candidates = allCandidates,
            profile = profile,
            id = { it.id },
            source = { it.source.name },
            titles = { listOf(it.title) + it.altTitles },
            tags = { manga -> manga.tags.map { it.title } },
            now = now,
            seed = seed,
            firstSuggestedAt = { ages[it.id] },
            preferredSources = preferredSources,
            languagePriority = {
                appSettings.suggestionsLanguage.accepts(it.source.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language)
            },
            groupOf = {
                SuggestionOrigins.groupOf(it.source.getContentType().name, it.tags.map { tag -> tag.title }, it.source.getLocale()?.toLanguageTag())
            },
            limits = if (append) SuggestionLimits(maxResults = APPEND_BATCH) else SuggestionLimits(),
            quality = { item ->
                val rating = ratings[item.id]
                SuggestionQuality.of(item.rating, rating?.score, rating?.weight)
            },
        ).map { ContentSuggestion(it.item, it.relevance, it.direction, it.reason) }
        if (append) {
            val added = suggestionRepository.append(suggestions)
            appSettings.suggestionsCursors = SuggestionCursors.encode(
                SuggestionCursors.advance(cursors.orEmpty(), answeredSources),
            )
            return added
        }
        suggestionRepository.replace(suggestions)
        appSettings.suggestionsRotationSeed = seed
        appSettings.suggestionsCursors = emptySet()
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

    private suspend fun communityCandidates(
        library: List<LibraryWork>,
        sources: List<ContentSource>,
        preferredSources: Set<String>,
        current: List<Content>,
        eligible: (Content) -> Boolean,
    ): List<SuggestionCandidate<Content>> {
        val targets = withTimeoutOrNull(10_000L) {
            runCatchingCancellable { communitySuggestions.suggestionTargets(library) }.getOrDefault(emptyList())
        }.orEmpty()
        if (targets.isEmpty()) return emptyList()
        val enabled = sourcesRepository.getEnabledSources().mapTo(HashSet()) { it.name }
        val used = library.mapNotNullTo(HashSet()) { it.source }
        val orderedSources = sources.filter { it.name in enabled && !it.isNsfw() }
            .sortedWith(compareByDescending<ContentSource> { it.name in preferredSources }
                .thenByDescending { it.name in used })
            .groupBy { CommunityMedium.of(it.getContentType().name) }
        val cached = (current + suggestionRepository.observeAll().first()).groupBy { it.source.name }
        return CommunityRecommendations.resolve(
            targets = targets,
            sources = orderedSources,
            cached = { cached[it.name].orEmpty() },
            search = { source, title ->
                runCatchingCancellable {
                    mangaRepositoryFactory.create(source).getList(0, null, ContentListFilter(query = title))
                }.getOrDefault(emptyList())
            },
            titles = { listOf(it.title) + it.altTitles },
            eligible = { source, item -> item.source.name == source.name && item.title.isNotBlank() &&
                (item.url.isNotBlank() || item.publicUrl.isNotBlank()) && eligible(item) },
            fold = CommunityRepository.Companion::fold,
        )
    }

    private suspend fun getSources(seed: Long): List<ContentSource> {
        val sources = if (appSettings.isSuggestionsIncludeDisabledSources) {
            sourcesRepository.getAllAvailableSourcesForActiveTvBoxRepository().sortedBy { it.name }
                .shuffled(Random(seed)).sortedWith(compareBy(nullsLast(LocaleComparator())) { it.getLocale() })
        } else {
            sourcesRepository.getEnabledSources().sortedBy { it.name }.shuffled(Random(seed))
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
        cursor: Int?,
        stored: Set<Long>,
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
        fun Content.isKnown() = id in stored || profile.knows(id, listOf(title) + altTitles)
        // A full refresh reads the top of each list; incremental loading continues [cursor] pages further.
        val pageIndexed = repository.listPagingMode == ContentRepository.ListPagingMode.PAGE_INDEX
        val firstOffset = when {
            cursor == null -> 0
            pageIndexed -> cursor
            else -> cursor * limit
        }
        queries.flatMap { query ->
            val order = SortOrder.valueOf(query.order)
            val filter = ContentListFilter(tags = setOfNotNull(query.tag))
            // One list failing must not cost the source its other one
            val first = runCatchingCancellable { repository.getList(offset = firstOffset, order = order, filter = filter) }
                .onSuccess { onAnswered() }
                .onFailure { onSourceFailure(it) }
                .getOrDefault(emptyList())
            // A reader with a big library already has the top of any list: one page further when little is left.
            val next = if (first.isNotEmpty() && first.count { !it.isKnown() } < limit / 2) {
                val offset = if (pageIndexed) firstOffset + 1 else firstOffset + first.size
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

        /** Loads further suggestions below the stored ones; ignored while any suggestion run is already going. */
        suspend fun startMore() {
            if (workManager.awaitWorkInfosByTag(TAG_ONESHOT).any { !it.state.isFinished }) {
                return
            }
            val request = OneTimeWorkRequestBuilder<SuggestionsWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(DATA_APPEND to true))
                .addTag(TAG_ONESHOT)
                .build()
            workManager.enqueue(request).await()
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
                .setInputData(workDataOf(DATA_REFRESH_AT to System.currentTimeMillis()))
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
        const val DATA_APPEND = "append"
        const val DATA_REFRESH_AT = "refresh_at"

        /** Suggestions kept in all; incremental loading stops adding beyond it. */
        const val MAX_STORED = 800

        /** Suggestions one incremental run adds at most. */
        const val APPEND_BATCH = 120
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
