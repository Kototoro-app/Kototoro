package org.skepsun.kototoro.core.parser

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.entity.ContentRating
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentChapters
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.domain.isLocalEntityBindingSource
import org.skepsun.kototoro.entitygraph.domain.toTrackingServiceOrNull
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class ContentDataRepository @Inject constructor(
	private val db: MangaDatabase,
	private val resolverProvider: Provider<ContentLinkResolver>,
	private val appShortcutManagerProvider: Provider<AppShortcutManager>,
	private val projectionIdentityResolver: ProjectionIdentityResolver,
) {

	sealed interface MetadataSourceSelection {
		data object Base : MetadataSourceSelection
		data class Tracking(
			val serviceId: Int,
			val remoteId: Long,
		) : MetadataSourceSelection
	}

	data class IgnoredTrackingSuggestion(
		val serviceId: Int,
		val remoteId: Long,
	)

	suspend fun saveReaderMode(manga: Content, mode: ReaderMode) {
		db.withTransaction {
			val stored = storeContentAndReturn(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(stored.id) ?: newEntity(stored.id)
			db.getPreferencesDao().upsert(entity.copy(mode = mode.id))
		}
	}

	suspend fun saveColorFilter(manga: Content, colorFilter: ReaderColorFilter?) {
		db.withTransaction {
			val stored = storeContentAndReturn(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(stored.id) ?: newEntity(stored.id)
			db.getPreferencesDao().upsert(
				entity.copy(
					cfBrightness = colorFilter?.brightness ?: 0f,
					cfContrast = colorFilter?.contrast ?: 0f,
					cfInvert = colorFilter?.isInverted == true,
					cfGrayscale = colorFilter?.isGrayscale == true,
				),
			)
		}
	}

	suspend fun resetColorFilters() {
		db.getPreferencesDao().resetColorFilters()
	}

	suspend fun getReaderMode(mangaId: Long): ReaderMode? {
		return db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }
	}

	suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? {
		return db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()
	}

	suspend fun getOverride(mangaId: Long): ContentOverride? {
		findEntityPrefsForMangaId(mangaId)?.getOverrideOrNull()?.let { return it }
		return db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()
	}

	suspend fun getMetadataSourceSelection(mangaId: Long): MetadataSourceSelection? {
		findEntityPrefsForMangaId(mangaId)?.getMetadataSourceSelectionOrNull()?.let { return it }
		val entity = db.getPreferencesDao().find(mangaId) ?: return null
		return entity.getMetadataSourceSelectionOrNull()
	}

	suspend fun getMetadataSourceSelections(mangaIds: Collection<Long>): LongObjectMap<MetadataSourceSelection> {
		if (mangaIds.isEmpty()) return MutableLongObjectMap(0)
		val map = MutableLongObjectMap<MetadataSourceSelection>(mangaIds.size)
		val entityIdsByMangaId = mangaIds.associateWith { mangaId ->
			db.getEntityGraphDao().findActiveBinding("local_manga", mangaId.toString())?.entityId
				?: db.getEntityGraphDao().findActiveBinding("0", mangaId.toString())?.entityId
		}
		val entitySelections = getEntityMetadataSourceSelections(entityIdsByMangaId.values.filterNotNull().distinct())
		entityIdsByMangaId.forEach { (mangaId, entityId) ->
			val selection = entityId?.let(entitySelections::get) ?: return@forEach
			map[mangaId] = selection
		}
		val remainingMangaIds = mangaIds.filterNot { map.containsKey(it) }
		if (remainingMangaIds.isEmpty()) {
			return map
		}
		val entities = db.getPreferencesDao().getLegacyMetadataSourceSelections(remainingMangaIds.toList())
		for (entity in entities) {
			map[entity.mangaId] = entity.getMetadataSourceSelectionOrNull() ?: continue
		}
		return map
	}

	suspend fun getEntityMetadataSourceSelection(entityId: Long): MetadataSourceSelection? {
		return db.getEntityGraphDao().findEntityPrefs(entityId)?.getMetadataSourceSelectionOrNull()
	}

	suspend fun getEntityMetadataSourceSelections(
		entityIds: Collection<Long>,
	): Map<Long, MetadataSourceSelection> {
		if (entityIds.isEmpty()) return emptyMap()
		val prefs = db.getEntityGraphDao().findEntityPrefsByIds(entityIds.distinct())
		return buildMap(prefs.size) {
			for (entity in prefs) {
				val selection = entity.getMetadataSourceSelectionOrNull() ?: continue
				put(entity.entityId, selection)
			}
		}
	}

	suspend fun setEntityMetadataSourceSelection(
		entityId: Long,
		selection: MetadataSourceSelection?,
	) {
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			if (dao.findEntity(entityId) == null) {
				return@withTransaction
			}
			dao.insertEntityPrefsIgnore(newEntityPrefs(entityId))
			updateEntityMetadataSourceSelection(
				entityId = entityId,
				selection = selection,
				updatedAt = System.currentTimeMillis(),
			)
		}
	}

	suspend fun setEntityPreferredLocalMangaId(entityId: Long, mangaId: Long?) {
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			if (dao.findEntity(entityId) == null) {
				return@withTransaction
			}
			dao.insertEntityPrefsIgnore(newEntityPrefs(entityId))
			dao.updateEntityPreferredLocalMangaId(
				entityId = entityId,
				preferredLocalMangaId = mangaId,
				updatedAt = System.currentTimeMillis(),
			)
		}
	}

	suspend fun getIgnoredTrackingSuggestion(mangaId: Long): IgnoredTrackingSuggestion? {
		// Tracking suggestion suppression remains projection-local on purpose.
		// It is a hint for one local manifestation, not a work-owned user state.
		val entity = db.getPreferencesDao().find(mangaId) ?: return null
		val serviceId = entity.ignoredTrackingSuggestionService ?: return null
		val remoteId = entity.ignoredTrackingSuggestionRemoteId ?: return null
		return IgnoredTrackingSuggestion(
			serviceId = serviceId,
			remoteId = remoteId,
		)
	}

	suspend fun getOverrides(): LongObjectMap<ContentOverride> {
		val map = MutableLongObjectMap<ContentOverride>()
		val entityPrefsById = db.getEntityGraphDao().dumpPrefs()
			.asSequence()
			.mapNotNull { prefs ->
				val override = prefs.getOverrideOrNull() ?: return@mapNotNull null
				prefs.entityId to override
			}
			.toMap()
		if (entityPrefsById.isNotEmpty()) {
			db.getEntityGraphDao().dumpBindings()
				.asSequence()
				.filter { it.source.isLocalEntityBindingSource() }
				.forEach { binding ->
					val localMangaId = binding.externalId.toLongOrNull() ?: return@forEach
					val override = entityPrefsById[binding.entityId] ?: return@forEach
					map[localMangaId] = override
				}
		}
		db.getPreferencesDao().getOverrides().forEach { entity ->
			if (map.containsKey(entity.mangaId)) {
				return@forEach
			}
			entity.getOverrideOrNull()?.let {
				map[entity.mangaId] = it
			}
		}
		return map
	}

	suspend fun getReadingStatus(mangaId: Long): ScrobblingStatus? {
		return findEntityPrefsForMangaId(mangaId)?.readingStatus
			?.let(ScrobblingStatus::valueOf)
			?: db.getPreferencesDao().find(mangaId)?.readingStatus?.let(ScrobblingStatus::valueOf)
	}

	fun observeReadingStatus(mangaId: Long): Flow<ScrobblingStatus?> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_PREFERENCES, TABLE_ENTITY_PREFERENCES),
			emitInitialState = true,
		)
			.map { getReadingStatus(mangaId) }
			.distinctUntilChanged()
	}

	suspend fun setReadingStatus(
		mangaId: Long,
		status: ScrobblingStatus?,
	) {
		db.withTransaction {
			val entityPrefs = findEntityPrefsForMangaId(mangaId)
			if (entityPrefs != null) {
				db.getEntityGraphDao().upsertPrefsRecord(
					entityPrefs.copy(
						readingStatus = status?.name,
						updatedAt = System.currentTimeMillis(),
					),
				)
			} else {
				val dao = db.getPreferencesDao()
				val entity = dao.find(mangaId) ?: newEntity(mangaId)
				dao.upsert(
					entity.copy(
						readingStatus = status?.name,
					),
				)
			}
		}
	}

	suspend fun setOverride(manga: Content, override: ContentOverride?) {
		db.withTransaction {
			val stored = storeContentAndReturn(manga, replaceExisting = false)
			val normalizedOverride = override.normalized()
			val entityPrefs = findEntityPrefsForMangaId(stored.id)
			if (entityPrefs != null) {
				val entityDao = db.getEntityGraphDao()
				entityDao.upsertPrefsRecord(
					entityPrefs.copy(
						titleOverride = normalizedOverride?.title,
						coverUrlOverride = normalizedOverride?.coverUrl,
						contentRatingOverride = normalizedOverride?.contentRating?.name,
						updatedAt = System.currentTimeMillis(),
					),
				)
				// Once a work owner exists, manual overrides are authoritative there.
				// Drop same-projection shadow overrides so runtime no longer keeps two truths.
				val prefsDao = db.getPreferencesDao()
				val legacyPrefs = prefsDao.find(stored.id)
				if (legacyPrefs != null &&
					(
						legacyPrefs.titleOverride != null ||
							legacyPrefs.coverUrlOverride != null ||
							legacyPrefs.contentRatingOverride != null
						)
				) {
					prefsDao.upsert(
						legacyPrefs.copy(
							titleOverride = null,
							coverUrlOverride = null,
							contentRatingOverride = null,
						),
					)
				}
			} else {
				val dao = db.getPreferencesDao()
				val entity = dao.find(stored.id) ?: newEntity(stored.id)
				dao.upsert(
					entity.copy(
						titleOverride = normalizedOverride?.title,
						coverUrlOverride = normalizedOverride?.coverUrl,
						contentRatingOverride = normalizedOverride?.contentRating?.name,
					),
				)
			}
			// Sync the manga table's nsfw/content_rating columns so SQL-level filters
			// (e.g. HistoryDao "manga.nsfw = 1") respect the manual override.
			val effectiveRating = normalizedOverride?.contentRating ?: stored.contentRating
			val effectiveNsfw = stored.copy(contentRating = effectiveRating).isNsfw()
			db.getMangaDao().updateContentRating(stored.id, effectiveNsfw, effectiveRating?.name)
		}
	}

	suspend fun setMetadataSourceSelection(
		mangaId: Long,
		selection: MetadataSourceSelection?,
	) {
		db.withTransaction {
			val entityPrefs = findEntityPrefsForMangaId(mangaId)
			entityPrefs?.let {
				updateEntityMetadataSourceSelection(
					entityId = it.entityId,
					selection = selection,
					updatedAt = System.currentTimeMillis(),
				)
			}
			if (entityPrefs != null) {
				// Work-level metadata authority is now owned by entity prefs.
				// Projection prefs should not mirror the same default selection anymore.
				return@withTransaction
			}
			// Legacy fallback only: keep projection-level storage for records that have not
			// been work/entity-bound yet. Once an entity exists, metadata authority lives there.
			upsertLegacyMetadataSourceSelection(
				mangaId = mangaId,
				selection = selection,
			)
		}
	}

	suspend fun setIgnoredTrackingSuggestion(
		mangaId: Long,
		suggestion: IgnoredTrackingSuggestion?,
	) {
		db.withTransaction {
			// Keep this on projection prefs. Ignoring a suggestion for one local source
			// should not implicitly suppress candidates for every projection in the work.
			val dao = db.getPreferencesDao()
			val entity = dao.find(mangaId) ?: newEntity(mangaId)
			dao.upsert(
				entity.copy(
					ignoredTrackingSuggestionService = suggestion?.serviceId,
					ignoredTrackingSuggestionRemoteId = suggestion?.remoteId,
				),
			)
		}
	}

	fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> {
		return db.getPreferencesDao().observe(mangaId)
			.map { it?.getColorFilterOrNull() }
			.distinctUntilChanged()
	}

	fun observeDisplayPreferencesChanges(): Flow<Int> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_PREFERENCES, TABLE_ENTITY_PREFERENCES, TABLE_ENTITY_GRAPH_BINDING),
			emitInitialState = true,
		)
			.map { displayPreferencesSignature() }
			.distinctUntilChanged()
	}

	private suspend fun displayPreferencesSignature(): Int {
		var result = 1
		db.getEntityGraphDao().dumpPrefs().forEach { prefs ->
			result = 31 * result + prefs.entityId.hashCode()
			result = 31 * result + prefs.preferredLocalMangaId.hashCode()
			result = 31 * result + prefs.titleOverride.hashCode()
			result = 31 * result + prefs.coverUrlOverride.hashCode()
			result = 31 * result + prefs.contentRatingOverride.hashCode()
			result = 31 * result + prefs.metadataSourceKind.hashCode()
			result = 31 * result + prefs.metadataBindingSource.hashCode()
			result = 31 * result + prefs.metadataBindingExternalId.hashCode()
			result = 31 * result + prefs.metadataSourceService.hashCode()
			result = 31 * result + prefs.metadataSourceRemoteId.hashCode()
		}
		db.getEntityGraphDao().dumpBindings()
			.asSequence()
			.filter { it.source.isLocalEntityBindingSource() }
			.forEach { binding ->
				result = 31 * result + binding.entityId.hashCode()
				result = 31 * result + binding.source.hashCode()
				result = 31 * result + binding.externalId.hashCode()
			}
		db.getPreferencesDao().findDisplayPreferenceRows().forEach { prefs ->
			result = 31 * result + prefs.mangaId.hashCode()
			result = 31 * result + prefs.titleOverride.hashCode()
			result = 31 * result + prefs.coverUrlOverride.hashCode()
			result = 31 * result + prefs.contentRatingOverride.hashCode()
			result = 31 * result + prefs.metadataSourceKind.hashCode()
			result = 31 * result + prefs.metadataSourceService.hashCode()
			result = 31 * result + prefs.metadataSourceRemoteId.hashCode()
		}
		return result
	}

	suspend fun findContentById(mangaId: Long, withChapters: Boolean): Content? {
		val chapters = if (withChapters) {
			db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
		} else {
			null
		}
		return db.getMangaDao().find(mangaId)?.toContent(chapters)
	}

	suspend fun findPreferredLocalContentById(mangaId: Long, withChapters: Boolean): Content? {
		val preferredLocalId = findEntityPrefsForMangaId(mangaId)?.preferredLocalMangaId
		return findContentById(preferredLocalId ?: mangaId, withChapters)
	}

	suspend fun findDisplayContentById(mangaId: Long, withChapters: Boolean): Content? {
		return findPreferredLocalContentById(mangaId, withChapters)
			?: findContentById(mangaId, withChapters)
	}

	suspend fun findContentByPublicUrl(publicUrl: String): Content? {
		return db.getMangaDao().findByPublicUrl(publicUrl)?.toContent()
	}

	suspend fun resolveIntent(intent: ContentIntent, withChapters: Boolean): Content? {
		val mangaId = intent.mangaId
		if (mangaId != 0L) {
			val intentManga = intent.manga
			findContentById(mangaId, withChapters)?.let { cached ->
				if (intentManga == null || cached.hasSameRemoteIdentity(intentManga)) {
					return cached
				}
			}
		}
		intent.manga?.let { return it.withCachedChaptersIfNeeded(withChapters) }
		intent.uri?.let { return resolverProvider.get().resolve(it).withCachedChaptersIfNeeded(withChapters) }
		return null
	}

	private fun Content.hasSameRemoteIdentity(other: Content): Boolean {
		val hasSameUrl = url.isNotBlank() && url == other.url
		val hasSamePublicUrl = publicUrl.isNotBlank() && publicUrl == other.publicUrl
		return source.name == other.source.name &&
			(hasSameUrl || hasSamePublicUrl)
	}

	suspend fun storeContent(manga: Content, replaceExisting: Boolean) {
		storeContentAndReturn(manga, replaceExisting)
	}

	suspend fun storeContentAndReturn(manga: Content, replaceExisting: Boolean): Content {
		return db.withTransaction {
			val stored = projectionIdentityResolver.resolveStoredProjection(manga)
			if (!replaceExisting && db.getMangaDao().find(stored.id) != null) {
				return@withTransaction stored
			}
			// avoid storing local manga if remote one is already stored
			val existing = if (stored.isLocal) {
				db.getMangaDao().find(stored.id)?.manga
			} else {
				null
			}
			if (existing == null || existing.source == stored.source.name) {
				val tags = stored.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(stored.toEntity(), tags)
				if (!stored.isLocal) {
					stored.chapters?.let { chapters ->
						db.getChaptersDao().replaceAll(stored.id, chapters.withIndex().toEntities(stored.id))
					}
				}
			}
			stored
		}
	}

	suspend fun resolveStoredProjection(content: Content): Content {
		return projectionIdentityResolver.resolveStoredProjection(content)
	}

	suspend fun updateProjectionSnapshot(manga: Content): Content {
		return db.withTransaction {
			upsertProjectionSnapshot(resolveStoredProjection(manga))
		}
	}

	suspend fun updateProjectionSnapshotAtAnchor(manga: Content, anchorMangaId: Long): Content {
		return db.withTransaction {
			check(db.getMangaDao().contains(anchorMangaId)) { "Unknown projection anchor: $anchorMangaId" }
			upsertProjectionSnapshot(manga.copy(id = anchorMangaId))
		}
	}

	private suspend fun upsertProjectionSnapshot(stored: Content): Content {
		val tags = stored.tags.toEntities()
		db.getTagsDao().upsert(tags)
		db.getMangaDao().upsert(stored.toEntity(), tags)
		if (!stored.isLocal) {
			stored.chapters?.let { chapters ->
				db.getChaptersDao().replaceAll(stored.id, chapters.withIndex().toEntities(stored.id))
			}
		}
		return stored
	}

	suspend fun gcChaptersCache() {
		db.getChaptersDao().gc()
	}

	suspend fun findTags(source: ContentSource): Set<ContentTag> {
		return db.getTagsDao().findTags(source.name).toContentTags()
	}

	fun observeAllTagTitles(): Flow<List<String>> = db.getTagsDao().observeAllTitles()

	suspend fun cleanupLocalContent() {
		val dao = db.getMangaDao()
		val broken = listOf(LocalMangaSource.name, org.skepsun.kototoro.core.model.LocalNovelSource.name, org.skepsun.kototoro.core.model.LocalVideoSource.name)
			.flatMap { dao.findAllBySource(it) }
			.filter { x -> x.manga.url.toUri().toFileOrNull()?.exists() == false }
		if (broken.isNotEmpty()) {
			dao.delete(broken.map { it.manga })
		}
	}

	suspend fun cleanupDatabase() {
		db.withTransaction {
			gcChaptersCache()
			val idsFromShortcuts = appShortcutManagerProvider.get().getContentShortcuts()
			val preservedLocalIds = idsFromShortcuts.mapNotNullTo(LinkedHashSet()) { shortcutId ->
				findDisplayContentById(shortcutId, withChapters = false)?.id
					?: findContentById(shortcutId, withChapters = false)?.id
			}
			db.getMangaDao().cleanup(preservedLocalIds)
		}
	}

	fun observeOverridesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_PREFERENCES, TABLE_ENTITY_PREFERENCES),
		emitInitialState = emitInitialState,
	)

	fun observeFavoritesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_WORK_FAVOURITES, TABLE_FAVOURITE_CATEGORIES),
		emitInitialState = emitInitialState,
	)

	private suspend fun Content.withCachedChaptersIfNeeded(flag: Boolean): Content = if (flag && !isLocal && chapters.isNullOrEmpty()) {
		val cachedChapters = db.getChaptersDao().findAll(id)
		if (cachedChapters.isEmpty()) {
			this
		} else {
			copy(chapters = cachedChapters.toContentChapters())
		}
	} else {
		this
	}

	private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? {
		return if (cfBrightness != 0f || cfContrast != 0f || cfInvert || cfGrayscale || cfBookEffect) {
			ReaderColorFilter(
				brightness = cfBrightness,
				contrast = cfContrast,
				isInverted = cfInvert,
				isGrayscale = cfGrayscale,
				isBookBackground = cfBookEffect
			)
		} else {
			null
		}
	}

	private fun MangaPrefsEntity.getOverrideOrNull(): ContentOverride? {
		return if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty()) {
			null
		} else {
			ContentOverride(
				coverUrl = coverUrlOverride?.nullIfEmpty(),
				title = titleOverride?.nullIfEmpty(),
				contentRating = ContentRating(contentRatingOverride),
			)
		}
	}

	private fun MangaPrefsEntity.getMetadataSourceSelectionOrNull(): MetadataSourceSelection? {
		return metadataSourceSelectionOrNull(
			metadataSourceKind = metadataSourceKind,
			metadataSourceService = metadataSourceService,
			metadataSourceRemoteId = metadataSourceRemoteId,
		)
	}

	private fun EntityPrefsRecord.getMetadataSourceSelectionOrNull(): MetadataSourceSelection? {
		return metadataSourceSelectionOrNull(
			metadataSourceKind = metadataSourceKind,
			metadataBindingSource = metadataBindingSource,
			metadataBindingExternalId = metadataBindingExternalId,
			metadataSourceService = metadataSourceService,
			metadataSourceRemoteId = metadataSourceRemoteId,
		)
	}

	private fun EntityPrefsRecord.getOverrideOrNull(): ContentOverride? {
		return if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty()) {
			null
		} else {
			ContentOverride(
				coverUrl = coverUrlOverride?.nullIfEmpty(),
				title = titleOverride?.nullIfEmpty(),
				contentRating = ContentRating(contentRatingOverride),
			)
		}
	}

	private fun metadataSourceSelectionOrNull(
		metadataSourceKind: String?,
		metadataBindingSource: String? = null,
		metadataBindingExternalId: String? = null,
		metadataSourceService: Int?,
		metadataSourceRemoteId: Long?,
	): MetadataSourceSelection? {
		return when (metadataSourceKind) {
			null -> null
			"base" -> MetadataSourceSelection.Base
			"tracking" -> {
				val serviceId = metadataBindingSource
					?.toTrackingServiceOrNull()
					?.id
					?: metadataSourceService
					?: return null
				val remoteId = metadataBindingExternalId?.toLongOrNull() ?: metadataSourceRemoteId ?: return null
				MetadataSourceSelection.Tracking(
					serviceId = serviceId,
					remoteId = remoteId,
				)
			}
			else -> null
		}
	}

	private suspend fun updateEntityMetadataSourceSelection(
		entityId: Long,
		selection: MetadataSourceSelection?,
		updatedAt: Long,
	) {
		val trackingSelection = selection.toTrackingSelectionOrNull()
		db.getEntityGraphDao().updateEntityMetadataSourceSelection(
			entityId = entityId,
			metadataSourceKind = selection.toMetadataSourceKind(),
			metadataBindingSource = trackingSelection?.serviceId?.toString(),
			metadataBindingExternalId = trackingSelection?.remoteId?.toString(),
			metadataSourceService = trackingSelection?.serviceId,
			metadataSourceRemoteId = trackingSelection?.remoteId,
			updatedAt = updatedAt,
		)
	}

	private suspend fun upsertLegacyMetadataSourceSelection(
		mangaId: Long,
		selection: MetadataSourceSelection?,
	) {
		val trackingSelection = selection.toTrackingSelectionOrNull()
		val dao = db.getPreferencesDao()
		val entity = dao.find(mangaId) ?: newEntity(mangaId)
		dao.upsert(
			entity.copy(
				metadataSourceKind = selection.toMetadataSourceKind(),
				metadataSourceService = trackingSelection?.serviceId,
				metadataSourceRemoteId = trackingSelection?.remoteId,
			),
		)
	}

	private fun MetadataSourceSelection?.toMetadataSourceKind(): String? {
		return when (this) {
			null -> null
			MetadataSourceSelection.Base -> "base"
			is MetadataSourceSelection.Tracking -> "tracking"
		}
	}

	private fun MetadataSourceSelection?.toTrackingSelectionOrNull(): MetadataSourceSelection.Tracking? {
		return this as? MetadataSourceSelection.Tracking
	}

	private fun newEntityPrefs(entityId: Long) = EntityPrefsRecord(
		entityId = entityId,
		preferredLocalMangaId = null,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		readingStatus = null,
		metadataSourceKind = null,
		metadataBindingSource = null,
		metadataBindingExternalId = null,
		metadataSourceService = null,
		metadataSourceRemoteId = null,
		updatedAt = System.currentTimeMillis(),
	)

	private fun newEntity(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = ReaderColorFilter.EMPTY.brightness,
		cfContrast = ReaderColorFilter.EMPTY.contrast,
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		metadataSourceKind = null,
		metadataSourceService = null,
		metadataSourceRemoteId = null,
		readingStatus = null,
		ignoredTrackingSuggestionService = null,
		ignoredTrackingSuggestionRemoteId = null,
	)

	private suspend fun findEntityPrefsForMangaId(mangaId: Long): EntityPrefsRecord? {
		val entityId = db.getEntityGraphDao().findActiveBinding("local_manga", mangaId.toString())?.entityId
			?: db.getEntityGraphDao().findActiveBinding("0", mangaId.toString())?.entityId
			?: return null
		val dao = db.getEntityGraphDao()
		if (dao.findEntity(entityId) == null) {
			return null
		}
		dao.insertEntityPrefsIgnore(newEntityPrefs(entityId))
		return dao.findEntityPrefs(entityId)
	}

	private fun ContentOverride?.normalized(): ContentOverride? {
		return this?.let {
			val normalized = ContentOverride(
				coverUrl = it.coverUrl?.nullIfEmpty(),
				title = it.title?.nullIfEmpty(),
				contentRating = it.contentRating,
			)
			if (normalized.coverUrl == null && normalized.title == null && normalized.contentRating == null) {
				null
			} else {
				normalized
			}
		}
	}
}
