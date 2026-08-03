package org.skepsun.kototoro.list.domain

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.IntDef
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import org.skepsun.kototoro.core.db.MangaDatabase
import javax.inject.Inject

@Reusable
class ContentListMapper @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localContentIndex: LocalContentIndex,
	private val dataRepository: ContentDataRepository,
	private val trackingSiteCacheRepository: TrackingSiteCacheRepository,
	private val db: MangaDatabase,
) {

	data class ListModelRequest(
		val manga: Content,
		val metadataSelectionOverride: ContentDataRepository.MetadataSourceSelection? = null,
		val useMetadataSelectionOverride: Boolean = false,
	)

	private val dict by lazy { readTagsDict(context) }

	fun observeDisplayChanges(): Flow<Unit> = merge(
		dataRepository.observeDisplayPreferencesChanges().map { Unit },
		favouritesRepository.observeFavouriteBadgeChanges(),
		trackingSiteCacheRepository.observeDetailsUpdates().map { Unit },
		settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) { globalTagBlacklist }.map { Unit },
	)

	suspend fun toListModelList(
		manga: Collection<Content>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): List<ContentListModel> = ArrayList<ContentListModel>(manga.size).apply {
		toListModelList(
			destination = this,
			manga = manga,
			mode = mode,
			flags = flags,
		)
	}

	suspend fun toRequestedListModelList(
		requests: Collection<ListModelRequest>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		pinnedIds: Set<Long>? = null,
	): List<ContentListModel> = ArrayList<ContentListModel>(requests.size).apply {
		toRequestedListModelList(
			destination = this,
			requests = requests,
			mode = mode,
			flags = flags,
			pinnedIds = pinnedIds,
		)
	}

	suspend fun toListModelList(
		destination: MutableCollection<in ContentListModel>,
		manga: Collection<Content>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		pinnedIds: Set<Long>? = null,
	) {
		val options = getOptions(flags)
		val mangaIds = manga.map { it.id }
		val manualOverrides = dataRepository.getOverrides()
		val metadataSelections = dataRepository.getMetadataSourceSelections(mangaIds)
		val counters = getCounters(mangaIds)
		val progress = getProgress(mangaIds, options)
		val trackingDetailsCache = HashMap<Pair<Int, Long>, TrackingSiteItemDetails?>()
		manga.mapTo(destination) {
			val metadataSelection = metadataSelections[it.id]
			toListModelImpl(
				manga = it,
				mode = mode,
				options = options,
				pinnedIds = pinnedIds,
				counters = counters,
				progress = progress,
				metadataTrackingService = getMetadataTrackingService(metadataSelection),
				override = resolveDisplayOverride(
					manga = it,
					manualOverride = manualOverrides[it.id],
					metadataSelection = metadataSelection,
					trackingDetailsCache = trackingDetailsCache,
				),
			)
		}
	}

	suspend fun toRequestedListModelList(
		destination: MutableCollection<in ContentListModel>,
		requests: Collection<ListModelRequest>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		pinnedIds: Set<Long>? = null,
	) {
		val options = getOptions(flags)
		val mangaIds = requests.map { it.manga.id }
		val metadataIds = requests.asSequence()
			.filterNot(ListModelRequest::useMetadataSelectionOverride)
			.map { it.manga.id }
			.distinct()
			.toList()
		val manualOverrides = dataRepository.getOverrides()
		val metadataSelections = if (metadataIds.isEmpty()) {
			MutableLongObjectMap<ContentDataRepository.MetadataSourceSelection>(0)
		} else {
			dataRepository.getMetadataSourceSelections(metadataIds)
		}
		val counters = getCounters(mangaIds)
		val progress = getProgress(mangaIds, options)
		val trackingDetailsCache = HashMap<Pair<Int, Long>, TrackingSiteItemDetails?>()
		requests.mapTo(destination) { request ->
			val metadataSelection = if (request.useMetadataSelectionOverride) {
				request.metadataSelectionOverride
			} else {
				metadataSelections[request.manga.id]
			}
			toListModelImpl(
				manga = request.manga,
				mode = mode,
				options = options,
				pinnedIds = pinnedIds,
				counters = counters,
				progress = progress,
				metadataTrackingService = getMetadataTrackingService(metadataSelection),
				override = resolveDisplayOverride(
					manga = request.manga,
					manualOverride = manualOverrides[request.manga.id],
					metadataSelection = metadataSelection,
					trackingDetailsCache = trackingDetailsCache,
				),
			)
		}
	}

	suspend fun toListModel(
		manga: Content,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
		metadataSelectionOverride: ContentDataRepository.MetadataSourceSelection? = null,
		useMetadataSelectionOverride: Boolean = false,
	): ContentListModel {
		val metadataSelection = if (useMetadataSelectionOverride) {
			metadataSelectionOverride
		} else {
			dataRepository.getMetadataSourceSelection(manga.id)
		}
		return toListModelImpl(
			manga = manga,
			mode = mode,
			options = getOptions(flags),
			counters = null,
			progress = null,
			metadataTrackingService = getMetadataTrackingService(metadataSelection),
			override = resolveDisplayOverride(
				manga = manga,
				manualOverride = dataRepository.getOverride(manga.id),
				metadataSelection = metadataSelection,
				trackingDetailsCache = HashMap(1),
			),
		)
	}

	suspend fun toFeedItem(logItem: TrackingLogItem) = FeedItem(
		id = logItem.id,
		entityId = logItem.entityId,
		preferredLocalMangaId = logItem.preferredLocalMangaId,
		override = resolveDisplayOverride(
			manga = logItem.manga,
			manualOverride = dataRepository.getOverride(logItem.manga.id),
			metadataSelection = dataRepository.getMetadataSourceSelection(logItem.manga.id),
			trackingDetailsCache = HashMap(1),
		),
		count = logItem.count ?: logItem.chapters.size,
		manga = logItem.manga,
		isNew = logItem.isNew,
	)

	suspend fun toFeedItems(logItems: List<TrackingLogItem>): List<FeedItem> {
		if (logItems.isEmpty()) {
			return emptyList()
		}
		val mangaIds = logItems.map { it.manga.id }
		val manualOverrides = dataRepository.getOverrides()
		val metadataSelections = dataRepository.getMetadataSourceSelections(mangaIds)
		val trackingDetailsCache = HashMap<Pair<Int, Long>, TrackingSiteItemDetails?>()
		val chapterCounts = db.getChaptersDao().findAllByMangaIds(mangaIds)
			.groupBy { it.mangaId }
			.mapValues { it.value.size }
		return logItems.map { logItem ->
			FeedItem(
				id = logItem.id,
				entityId = logItem.entityId,
				preferredLocalMangaId = logItem.preferredLocalMangaId,
				override = resolveDisplayOverride(
					manga = logItem.manga,
					manualOverride = manualOverrides[logItem.manga.id],
					metadataSelection = metadataSelections[logItem.manga.id],
					trackingDetailsCache = trackingDetailsCache,
				),
				count = logItem.count ?: logItem.chapters.size,
				manga = logItem.manga,
				isNew = logItem.isNew,
				totalChapters = chapterCounts[logItem.manga.id] ?: 0,
			)
		}
	}

	fun mapTags(tags: Collection<ContentTag>) = tags.map {
		ChipsView.ChipModel(
			tint = getTagTint(it),
			title = it.title,
			data = it,
		)
	}

	private suspend fun toCompactListModel(
		manga: Content,
		@Options options: Int,
		pinnedIds: Set<Long>?,
		counters: Map<Long, Int>?,
		progress: Map<Long, ReadingProgress>?,
		metadataTrackingService: ScrobblerService?,
		override: ContentOverride?,
	) = ContentCompactListModel(
		manga = manga,
		override = override,
		subtitle = manga.tags.joinToString(", ") { it.title }.ifBlank { null },
		counter = getCounter(manga.id, options, counters),
		progress = getProgress(manga.id, options, progress),
		isPinned = isPinned(manga.id, options, pinnedIds),
		metadataTrackingService = metadataTrackingService,
	)

	private suspend fun toDetailedListModel(
		manga: Content,
		@Options options: Int,
		pinnedIds: Set<Long>?,
		counters: Map<Long, Int>?,
		progress: Map<Long, ReadingProgress>?,
		metadataTrackingService: ScrobblerService?,
		override: ContentOverride?,
	) = ContentDetailedListModel(
		subtitle = manga.altTitles.firstOrNull(),
		manga = manga,
		override = override,
		counter = getCounter(manga.id, options, counters),
		progress = getProgress(manga.id, options, progress),
		isFavorite = isFavorite(manga.id, options),
		isSaved = isSaved(manga.id, options),
		tags = mapTags(manga.tags),
		isPinned = isPinned(manga.id, options, pinnedIds),
		metadataTrackingService = metadataTrackingService,
	)

	private suspend fun toGridModel(
		manga: Content,
		@Options options: Int,
		pinnedIds: Set<Long>?,
		counters: Map<Long, Int>?,
		progress: Map<Long, ReadingProgress>?,
		metadataTrackingService: ScrobblerService?,
		override: ContentOverride?
	) = ContentGridModel(
		manga = manga,
		override = override,
		subtitle = manga.altTitles.firstOrNull(),
		counter = getCounter(manga.id, options, counters),
		progress = getProgress(manga.id, options, progress),
		isFavorite = isFavorite(manga.id, options),
		isSaved = isSaved(manga.id, options),
		isPinned = isPinned(manga.id, options, pinnedIds),
		metadataTrackingService = metadataTrackingService,
	)

	private suspend fun toListModelImpl(
		manga: Content,
		mode: ListMode,
		@Options options: Int,
		pinnedIds: Set<Long>? = null,
		counters: Map<Long, Int>? = null,
		progress: Map<Long, ReadingProgress>? = null,
		metadataTrackingService: ScrobblerService? = null,
		override: ContentOverride?,
	): ContentListModel = when (mode) {
		ListMode.LIST -> toCompactListModel(manga, options, pinnedIds, counters, progress, metadataTrackingService, override)
		ListMode.DETAILED_LIST -> toDetailedListModel(
			manga,
			options,
			pinnedIds,
			counters,
			progress,
			metadataTrackingService,
			override,
		)
		ListMode.GRID,
		ListMode.COMPACT_GRID -> toGridModel(manga, options, pinnedIds, counters, progress, metadataTrackingService, override)
	}

	private suspend fun getCounters(mangaIds: Collection<Long>): Map<Long, Int>? {
		return if (settings.isTrackerEnabled) {
			trackingRepository.getNewChaptersCounts(mangaIds)
		} else {
			null
		}
	}

	private suspend fun getCounter(mangaId: Long, @Options options: Int, counters: Map<Long, Int>?): Int {
		return if (settings.isTrackerEnabled) {
			counters?.get(mangaId) ?: trackingRepository.getNewChaptersCount(mangaId)
		} else {
			0
		}
	}

	private suspend fun getProgress(mangaIds: Collection<Long>, @Options options: Int): Map<Long, ReadingProgress>? {
		return if (options.isBadgeEnabled(PROGRESS)) {
			historyRepository.getProgress(mangaIds, settings.progressIndicatorMode)
		} else {
			null
		}
	}

	private suspend fun getProgress(
		mangaId: Long,
		@Options options: Int,
		progress: Map<Long, ReadingProgress>?,
	): ReadingProgress? {
		return if (options.isBadgeEnabled(PROGRESS)) {
			progress?.get(mangaId) ?: historyRepository.getProgress(mangaId, settings.progressIndicatorMode)
		} else {
			null
		}
	}

	private suspend fun isFavorite(mangaId: Long, @Options options: Int): Boolean {
		return options.isBadgeEnabled(FAVORITE) && favouritesRepository.isFavoriteByWork(mangaId)
	}

	private suspend fun isPinned(mangaId: Long, @Options options: Int, pinnedIds: Set<Long>?): Boolean {
		return pinnedIds?.contains(mangaId) ?: favouritesRepository.isPinned(listOf(mangaId))
	}

	private suspend fun isSaved(mangaId: Long, @Options options: Int): Boolean {
		return options.isBadgeEnabled(SAVED) && mangaId in localContentIndex
	}

	private fun getMetadataTrackingService(
		selection: ContentDataRepository.MetadataSourceSelection?,
	): ScrobblerService? {
		selection as? ContentDataRepository.MetadataSourceSelection.Tracking
			?: return null
		return ScrobblerService.entries.firstOrNull { it.id == selection.serviceId }
	}

	private suspend fun resolveDisplayOverride(
		manga: Content,
		manualOverride: ContentOverride?,
		metadataSelection: ContentDataRepository.MetadataSourceSelection?,
		trackingDetailsCache: MutableMap<Pair<Int, Long>, TrackingSiteItemDetails?>,
	): ContentOverride? {
		val trackingOverride = (metadataSelection as? ContentDataRepository.MetadataSourceSelection.Tracking)
			?.let { trackingSelection ->
				val service = ScrobblerService.entries.firstOrNull { it.id == trackingSelection.serviceId }
					?: return@let null
				val cacheKey = trackingSelection.serviceId to trackingSelection.remoteId
				val details = trackingDetailsCache.getOrPut(cacheKey) {
					trackingSiteCacheRepository.readDetails(service, trackingSelection.remoteId)
				}
				ContentOverride(
					coverUrl = details?.coverUrl?.takeIf { it.isNotBlank() },
					title = details?.title?.takeIf { it.isNotBlank() },
					contentRating = null,
				)
			}
		val merged = ContentOverride(
			coverUrl = manualOverride?.coverUrl ?: trackingOverride?.coverUrl,
			title = manualOverride?.title ?: trackingOverride?.title,
			contentRating = manualOverride?.contentRating,
		)
		return if (
			merged.coverUrl == null &&
			merged.title == null &&
			merged.contentRating == null
		) {
			null
		} else {
			merged
		}
	}

	@ColorRes
	private fun getTagTint(tag: ContentTag): Int {
		return if (settings.isTagsWarningsEnabled && tag.title.lowercase() in dict) {
			R.color.warning
		} else {
			0
		}
	}

	private fun readTagsDict(context: Context): ScatterSet<String> =
		context.resources.openRawResource(R.raw.tags_warnlist).use {
			val set = MutableScatterSet<String>()
			it.bufferedReader().forEachLine { x ->
				val line = x.trim()
				if (line.isNotEmpty()) {
					set.add(line)
				}
			}
			set.trim()
			set
		}

	private fun Int.isBadgeEnabled(@Options badge: Int) = this and badge == badge

	@Options
	@SuppressLint("WrongConstant")
	private fun getOptions(@Flags flags: Int): Int {
		var options = settings.getContentListBadges() or PROGRESS
		options = options and flags.inv()
		return options
	}

	@IntDef(DEFAULTS, NO_SAVED, NO_PROGRESS, NO_FAVORITE, flag = true)
	@Retention(AnnotationRetention.SOURCE)
	annotation class Flags

	@IntDef(NONE, SAVED, FAVORITE, PROGRESS)
	@Retention(AnnotationRetention.SOURCE)
	private annotation class Options

	companion object {

		private const val NONE = 0
		private const val SAVED = 1
		private const val PROGRESS = 2
		private const val FAVORITE = 4

		const val DEFAULTS = NONE
		const val NO_SAVED = SAVED
		const val NO_PROGRESS = PROGRESS
		const val NO_FAVORITE = FAVORITE
	}
}
