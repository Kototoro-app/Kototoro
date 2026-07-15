package org.skepsun.kototoro.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaSourcesDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceListSource
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.isBroken
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.flattenLatest
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.mapNotNullToSet
import org.skepsun.kototoro.parsers.util.mapToSet
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.space.data.ProjectionContentTypeBackfill

private const val BrowseSourcesTraceTag = "BrowseSourcesTrace"

private inline fun traceBrowseSources(message: () -> String) {
	if (BuildConfig.DEBUG) {
		Log.d(BrowseSourcesTraceTag, message())
	}
}

private data class EnabledSourcesSnapshot(
	val sources: List<ContentSourceInfo>,
	val disabledNames: Set<String>,
	val enabledNames: Set<String>,
	val allEnabled: Boolean,
)

@Singleton
class ContentSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager,
	private val sourceTypeIdentifier: org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier,
	private val sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
	private val mihonExtensionManager: org.skepsun.kototoro.mihon.MihonExtensionManager,
	private val aniyomiExtensionManager: org.skepsun.kototoro.aniyomi.AniyomiExtensionManager,
	private val ireaderExtensionManager: org.skepsun.kototoro.ireader.IReaderExtensionManager,
	private val cloudstreamRuntimeManager: org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager,
	private val sourceAvailabilityRepository: SourceAvailabilityRepository,
	private val projectionContentTypeBackfill: ProjectionContentTypeBackfill,
) {

	private val dao get() = db.getSourcesDao()
	private val jsonDao get() = db.getJsonSourceDao()
	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val cachedKotatsuSources = java.util.concurrent.ConcurrentHashMap<String, org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource>()
	private val enabledSources: StateFlow<List<ContentSourceInfo>> =
		createEnabledSourcesFlow()
			.distinctUntilChanged()
			.flowOn(Dispatchers.Default)
			.stateIn(
				scope = processLifecycleScope,
				started = SharingStarted.Eagerly,
				initialValue = emptyList(),
			)
	private val enabledBrowseSources: StateFlow<List<ContentSourceInfo>> =
		createEnabledBrowseSourcesFlow()
			.onEach { sources ->
				traceBrowseSources {
					"browse_state emitted size=${sources.size} " +
						"types=${sources.groupingBy { it.mangaSource.getContentType() }.eachCount()}"
				}
			}
			.distinctUntilChanged()
			.flowOn(Dispatchers.Default)
			.stateIn(
				scope = processLifecycleScope,
				started = SharingStarted.Eagerly,
				initialValue = emptyList(),
			)

	init {
		processLifecycleScope.launch(Dispatchers.IO) {
			combine(
				observeJarParserSourceChanges(),
				observeExternalExtensionChanges(),
				cloudstreamRuntimeManager.sources,
			) { _, _, _ -> Unit }.collect {
				traceBrowseSources {
					"registry_change content=${org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources.value.size} " +
						"manga=${org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources.value.size} " +
						"mihon=${mihonExtensionManager.installedExtensions.value.size} " +
						"aniyomi=${aniyomiExtensionManager.installedExtensions.value.size} " +
						"ireader=${ireaderExtensionManager.installedExtensions.value.size} " +
						"cloudstream=${cloudstreamRuntimeManager.sources.value.size}"
				}
				cachedKotatsuSources.clear()
				assimilateNewSources(force = true)
			}
		}
	}

	val allContentSources: Set<ContentSource>
		get() {
			val set = LinkedHashSet<ContentSource>()
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources.value.forEach { set.add(it) }
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources.value.forEach { 
				set.add(cachedKotatsuSources.getOrPut(it.name) { org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource(it) }) 
			}
			cloudstreamRuntimeManager.sources.value.forEach { set.add(it) }
			return set
		}

	suspend fun getAllAvailableSourcesUnfiltered(): List<ContentSource> {
		assimilateNewSources()
		val sources = canonicalizeSourcesByName(buildList {
			addAll(allContentSources)
			addAll(getExternalSources())
			addAll(jsonSourceManager.observeAllJsonSources().first().map(::JsonContentSource))
			addAll(getEnabledMihonSources())
			addAll(getEnabledAniyomiSources())
			addAll(getEnabledIReaderSources())
		})
		projectionContentTypeBackfill.backfillAll(
			resolvedSources = sources + listOf(LocalMangaSource, LocalNovelSource, LocalVideoSource),
		)
		return sources
	}

	suspend fun getAllAvailableSourcesForListing(): List<ContentSource> {
		assimilateNewSources()
		return canonicalizeSourcesByName(buildList {
			addAll(allContentSources)
			addAll(getExternalSources())
			addAll(jsonDao.observeAllSummaries().first().map(::JsonSourceListSource))
			addAll(getEnabledMihonSources())
			addAll(getEnabledAniyomiSources())
			addAll(getEnabledIReaderSources())
		})
	}

	suspend fun getEnabledSources(): List<ContentSource> {
		normalizeAllEnabledFlagIfNeeded()
		assimilateNewSources()
		val order = settings.sourcesSortOrder
		val disabledNames = if (!settings.isAllSourcesEnabled) dao.findAll().filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
		
		return dao.findAll(!settings.isAllSourcesEnabled, order).toSources(settings.isNsfwContentDisabled, order)
			.let { enabledSources ->
				val external = getExternalSources()
				val jsonSources = getEnabledJsonSources()
				val mihonSources = getEnabledMihonSources()
				val aniyomiSources = getEnabledAniyomiSources()
				val ireaderSources = getEnabledIReaderSources()
				
				val list = ArrayList<ContentSource>()
				enabledSources.mapTo(list) { it.mangaSource }
				external.forEach { if (settings.isAllSourcesEnabled || it.name !in disabledNames) list.add(it) }
				jsonSources.forEach(list::add)
				mihonSources.forEach {
					if (settings.isAllSourcesEnabled || it.name !in disabledNames) list.add(it)
				}
				aniyomiSources.forEach {
					if (settings.isAllSourcesEnabled || it.name !in disabledNames) list.add(it)
				}
				ireaderSources.forEach {
					if (settings.isAllSourcesEnabled || it.name !in disabledNames) list.add(it)
				}
				
				if (!settings.isShowBrokenSources) {
					list.retainAll { !it.isBroken }
				}
				
				canonicalizeSourcesByName(list)
			}
	}
	
	/**
	 * Gets all enabled Mihon sources as MihonMangaSource instances.
	 * Filters sources based on user's app locale - only sources matching user's language are shown.
	 * 
	 * @return List of enabled Mihon sources
	 */
	private fun getEnabledMihonSources(): List<org.skepsun.kototoro.mihon.model.MihonMangaSource> {
		val allSources = mihonExtensionManager.getMihonMangaSources()
		val isNsfwDisabled = settings.isNsfwContentDisabled
		return allSources.filter { !isNsfwDisabled || !it.isNsfw }
	}
	
	/**
	 * Gets all enabled Aniyomi sources as AniyomiAnimeSource instances.
	 */
	private fun getEnabledAniyomiSources(): List<org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource> {
		val allSources = aniyomiExtensionManager.installedExtensions.value.flatMap { ext ->
			ext.catalogueSources.map { catalogueSource ->
				org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource(
					animeCatalogueSource = catalogueSource,
					pkgName = ext.pkgName,
					isNsfw = ext.isNsfw
				)
			}
		}
		
		val isNsfwDisabled = settings.isNsfwContentDisabled
		
		// Note: We bypass `isExtensionsFilterLangEnabled` for Aniyomi because anime viewers
		// often watch subbed content regardless of the extension's declared language, 
		// and Aniyomi extensions frequently mislabel locales or only offer 'en'/'pt-BR' etc.
		return allSources.filter { source ->
			if (isNsfwDisabled && source.isNsfw) return@filter false
			true
		}
	}
	
	/**
	 * Gets all enabled IReader sources as IReaderMangaSource instances.
	 */
	private fun getEnabledIReaderSources(): List<org.skepsun.kototoro.ireader.model.IReaderMangaSource> {
		val allSources = ireaderExtensionManager.getIReaderMangaSources()
		val isNsfwDisabled = settings.isNsfwContentDisabled
		return allSources.filter { !isNsfwDisabled || !it.isNsfw }
	}
	
	/**
	 * Observes all IReader sources.
	 */
	private fun observeIReaderSources(): Flow<List<org.skepsun.kototoro.ireader.model.IReaderMangaSource>> {
		return combine(
			ireaderExtensionManager.installedExtensions,
			observeIsNsfwDisabled()
		) { _, _ ->
			getEnabledIReaderSources()
		}
	}
	
	/**
	 * Gets all enabled JSON sources as ContentSource instances.
	 * 
	 * @return List of enabled JSON sources wrapped as ContentSource
	 */
	private suspend fun getEnabledJsonSources(): List<org.skepsun.kototoro.core.jsonsource.JsonContentSource> {
		val jsonSources = jsonSourceManager.observeEnabledJsonSources()
			.map { entities -> entities.map(::JsonContentSource) }
			.first()
		return jsonSources
	}

	suspend fun getPinnedSources(): Set<ContentSource> {
		assimilateNewSources()
		val skipNsfw = settings.isNsfwContentDisabled
		return canonicalizeSourcesByName(dao.findAllPinned().mapNotNull {
			it.source.toContentSourceOrNull()?.takeUnless { x -> skipNsfw && x.isNsfw() }
		}).toSet()
	}

	suspend fun getTopSources(limit: Int): List<ContentSource> {
		assimilateNewSources()
		return canonicalizeSourcesByName(
			dao.findLastUsed(limit).toSources(settings.isNsfwContentDisabled, null).map { it.mangaSource },
		).take(limit)
	}

	suspend fun getDisabledSources(): Set<ContentSource> {
		assimilateNewSources()
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val result = getAllAvailableSourcesUnfiltered().toMutableSet()
		val enabled = dao.findAllEnabledNames()
		for (name in enabled) {
			val source = name.toContentSourceOrNull() ?: continue
			result.remove(source)
		}
		return canonicalizeSourcesByName(result.toList()).toSet()
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		sortOrder: SourcesSortOrder?,
		sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>? = null,
	): List<ContentSource> {
		assimilateNewSources()
		
		// Filter by source type if specified
		val shouldIncludeNative = sourceTypes == null || 
			org.skepsun.kototoro.core.jsonsource.SourceType.NATIVE in sourceTypes
		val shouldIncludeJson = sourceTypes == null || 
			sourceTypes.any { it != org.skepsun.kototoro.core.jsonsource.SourceType.NATIVE }
		
		// Get native sources
		val sources = if (shouldIncludeNative) {
			val entities = dao.findAll().toMutableList()
			if (isDisabledOnly && !settings.isAllSourcesEnabled) {
				entities.removeAll { it.isEnabled }
			}
			if (isNewOnly) {
				entities.retainAll { it.addedIn == BuildConfig.VERSION_CODE }
			}
			entities.toSources(
				skipNsfwSources = settings.isNsfwContentDisabled,
				sortOrder = sortOrder,
			).run {
				mapTo(ArrayList<ContentSource>(size)) { it.mangaSource }
			}
		} else {
			ArrayList()
		}
		

		// Apply filters to all collected sources
		if (locale != null) {
			sources.retainAll { it.getLocale()?.language == locale }
		}
		if (excludeBroken) {
			sources.retainAll { !it.isBroken }
		}
		if (types.isNotEmpty()) {
			sources.retainAll { it.getContentType() in types }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.getTitle(context).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
			}
		}
		
		return sources
	}

	/**
	 * Queries all sources (native and JSON) with filtering options.
	 * 
	 * @param isDisabledOnly If true, only return disabled sources
	 * @param isNewOnly If true, only return newly added sources
	 * @param excludeBroken If true, exclude broken sources
	 * @param types Filter by content types (manga, novel, video)
	 * @param query Search query to filter by name
	 * @param locale Filter by locale
	 * @param sortOrder Sort order for results
	 * @param sourceTypes Filter by source types (NATIVE, JSON_LEGADO, JSON_TVBOX)
	 * @return List of sources matching the filters
	 */
	suspend fun queryAllSources(
		isDisabledOnly: Boolean = false,
		isNewOnly: Boolean = false,
		excludeBroken: Boolean = false,
		types: Set<ContentType> = emptySet(),
		query: String? = null,
		locale: String? = null,
		sortOrder: SourcesSortOrder? = null,
		sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>? = null,
		includeDisabledSources: Boolean = false,
	): List<ContentSource> {
		normalizeAllEnabledFlagIfNeeded()
		val result = mutableListOf<ContentSource>()
		
		// Add native sources if requested
		val shouldIncludeNative = sourceTypes == null || 
			org.skepsun.kototoro.core.jsonsource.SourceType.NATIVE in sourceTypes
		
		if (shouldIncludeNative) {
			val nativeSources = queryParserSources(
				isDisabledOnly = isDisabledOnly,
				isNewOnly = isNewOnly,
				excludeBroken = excludeBroken,
				types = types,
				query = query,
				locale = locale,
				sortOrder = sortOrder,
				sourceTypes = sourceTypes,
			)
			result.addAll(nativeSources)
		}
		
		// Add JSON sources if requested
		val shouldIncludeJson = sourceTypes == null || 
			sourceTypes.any { it != org.skepsun.kototoro.core.jsonsource.SourceType.NATIVE }
		
		if (shouldIncludeJson) {
			val jsonSources = queryJsonSources(
				isDisabledOnly = isDisabledOnly,
				query = query,
				sourceTypes = sourceTypes,
				includeDisabledSources = includeDisabledSources,
			)
			result.addAll(jsonSources)
		}
		
		// Add Mihon sources if requested
		val shouldIncludeMihon = sourceTypes == null || 
			org.skepsun.kototoro.core.jsonsource.SourceType.MIHON in sourceTypes
		
		if (shouldIncludeMihon) {
			val allMihon = mihonExtensionManager.getMihonMangaSources()
			val disabledNames = if (!settings.isAllSourcesEnabled) dao.findAll().filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val existingNames = result.mapToSet { it.name }
			val filteredMihon = allMihon.filter { source ->
				source.name !in existingNames &&
				(includeDisabledSources || if (isDisabledOnly) source.name in disabledNames else source.name !in disabledNames) &&
				(query.isNullOrEmpty() || source.displayName.contains(query, ignoreCase = true))
			}
			result.addAll(filteredMihon)
		}

		// Add Aniyomi sources if requested
		val shouldIncludeAniyomi = sourceTypes == null || 
			org.skepsun.kototoro.core.jsonsource.SourceType.ANIYOMI in sourceTypes
		
		if (shouldIncludeAniyomi) {
			val allAniyomi = aniyomiExtensionManager.installedExtensions.value.flatMap { ext ->
				ext.catalogueSources.map { catalogueSource ->
					org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource(
						animeCatalogueSource = catalogueSource,
						pkgName = ext.pkgName,
						isNsfw = ext.isNsfw
					)
				}
			}
			val disabledNames = if (!settings.isAllSourcesEnabled) dao.findAll().filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val existingNames = result.mapToSet { it.name }
			val filteredAniyomi = allAniyomi.filter { source ->
				source.name !in existingNames &&
				(includeDisabledSources || if (isDisabledOnly) source.name in disabledNames else source.name !in disabledNames) &&
				(query.isNullOrEmpty() || source.displayName.contains(query, ignoreCase = true))
			}
			result.addAll(filteredAniyomi)
		}

		// Add IReader sources if requested
		val shouldIncludeIReader = sourceTypes == null || 
			org.skepsun.kototoro.core.jsonsource.SourceType.IREADER in sourceTypes
		
		if (shouldIncludeIReader) {
			val allIReader = ireaderExtensionManager.getIReaderMangaSources()
			val disabledNames = if (!settings.isAllSourcesEnabled) dao.findAll().filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val existingNames = result.mapToSet { it.name }
			val filteredIReader = allIReader.filter { source ->
				source.name !in existingNames &&
				(includeDisabledSources || if (isDisabledOnly) source.name in disabledNames else source.name !in disabledNames) &&
				(query.isNullOrEmpty() || source.displayName.contains(query, ignoreCase = true))
			}
			result.addAll(filteredIReader)
		}

		val shouldIncludeCloudstream = sourceTypes == null ||
			org.skepsun.kototoro.core.jsonsource.SourceType.CLOUDSTREAM in sourceTypes

		if (shouldIncludeCloudstream) {
			val allCloudstream = cloudstreamRuntimeManager.sources.value
			val disabledNames = if (!settings.isAllSourcesEnabled) dao.findAll().filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val existingNames = result.mapToSet { it.name }
			val filteredCloudstream = allCloudstream.filter { source ->
				source.name !in existingNames &&
					(includeDisabledSources || if (isDisabledOnly) source.name in disabledNames else source.name !in disabledNames) &&
					(query.isNullOrEmpty() || source.displayName.contains(query, ignoreCase = true))
			}
			result.addAll(filteredCloudstream)
		}
		
		if (locale != null) {
			result.retainAll { it.getLocale()?.language == locale }
		}
		if (types.isNotEmpty()) {
			result.retainAll { it.getContentType() in types }
		}
		
		val activePresetId = settings.activeSourcePresetId
		if (activePresetId != -1L) {
			val preset = db.getSourcePresetsDao().find(activePresetId)
			if (preset != null) {
				val languages = preset.languages.split(',').mapNotNullTo(LinkedHashSet()) {
					it.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
				}
				result.retainAll { source ->
					source.getLocale()?.language?.lowercase(Locale.ROOT) in languages
				}
			}
		}
		
		return result
	}
	
	/**
	 * Queries JSON sources with filtering options.
	 * 
	 * @param isDisabledOnly If true, only return disabled sources
	 * @param query Search query to filter by name
	 * @param sourceTypes Filter by JSON source types (JSON_LEGADO, JSON_TVBOX)
	 * @return List of JSON sources matching the filters
	 */
	private suspend fun queryJsonSources(
		isDisabledOnly: Boolean,
		query: String?,
		sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>?,
		includeDisabledSources: Boolean,
	): List<JsonSourceListSource> {
		// Get all JSON sources
		val allJsonSources = jsonDao.observeAllSummaries().first()
		
		// Filter by enabled/disabled
		var filtered = when {
			includeDisabledSources -> allJsonSources
			isDisabledOnly -> allJsonSources.filter { !it.enabled }
			else -> allJsonSources.filter { it.enabled }
		}
		
		// Filter by source type
		if (sourceTypes != null) {
			filtered = filtered.filter { entity ->
				val sourceType = sourceTypeIdentifier.getSourceType(entity.id)
				sourceType in sourceTypes
			}
		}
		
		// Filter by query
		if (!query.isNullOrEmpty()) {
			filtered = filtered.filter { entity ->
				entity.name.contains(query, ignoreCase = true) ||
				entity.id.contains(query, ignoreCase = true)
			}
		}
		
		return filtered.map(::JsonSourceListSource)
	}
	
	fun observeIsEnabled(source: ContentSource): Flow<Boolean> {
		// Check if it's a JSON source
		if (sourceTypeIdentifier.isJsonSource(source.name)) {
			return jsonSourceManager.observeAllJsonSources().map { entities ->
				entities.find { it.id == source.name }?.enabled ?: false
			}
		}
		return dao.observeIsEnabled(source.name).onStart { assimilateNewSources() }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			}
		) { skipNsfw, sources ->
			sources.count {
				it.source.toContentSourceOrNull()?.let { s -> 
					(!skipNsfw || !s.isNsfw())
				} == true
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, enabledSources ->
			val enabled = enabledSources.mapToSet { it.source }
			allContentSources.count { x ->
				x.name !in enabled && (!skipNsfw || !x.isNsfw())
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeBuiltInSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources,
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources
		) { skipNsfw, _, _ ->
			allContentSources.count { !skipNsfw || !it.isNsfw() }
		}.distinctUntilChanged()
	}

	fun observeJsonSourcesCount(): Flow<Int> {
		return jsonSourceManager.observeAllJsonSources().map { it.count() }.distinctUntilChanged()
	}

	fun observeMihonSourcesCount(): Flow<Int> {
		// getEnabledMihonSources already respects content language filter if enabled
		return observeMihonSources().map { it.size }.distinctUntilChanged()
	}

	fun observeAniyomiSourcesCount(): Flow<Int> {
		return observeAniyomiSources().map { it.size }.distinctUntilChanged()
	}

	fun observeIReaderSourcesCount(): Flow<Int> {
		return observeIReaderSources().map { it.size }.distinctUntilChanged()
	}

	fun observeExternalExtensionChanges(): Flow<Unit> {
		return combine(
			mihonExtensionManager.installedExtensions,
			aniyomiExtensionManager.installedExtensions,
			ireaderExtensionManager.installedExtensions,
		) { _, _, _ ->
			Unit
		}
	}

	private fun observeJarParserSourceChanges(): Flow<Unit> {
		return combine(
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources,
			org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources,
		) { _, _ ->
			Unit
		}
	}

	fun observeEnabledSources(): StateFlow<List<ContentSourceInfo>> = enabledSources

	private fun createEnabledSourcesFlow(): Flow<List<ContentSourceInfo>> = combine(
		observeIsNsfwDisabled(),
		observeAllEnabled(),
		observeSortOrder(),
		settings.observeAsFlow(AppSettings.KEY_SHOW_BROKEN_SOURCES) { isShowBrokenSources }
	) { skipNsfw, allEnabled, order, showBroken ->

		combine(
			dao.observeAll(false, order),
			observeJarParserSourceChanges(),
			mihonExtensionManager.installedExtensions,
			aniyomiExtensionManager.installedExtensions,
			jsonSourceManager.observeEnabledJsonSources()
		) { entities, _, _, _, _ ->
			val disabledNames = if (!allEnabled) entities.filter { !it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val enabledNames = if (!allEnabled) entities.filter { it.isEnabled }.mapToSet { it.source } else emptySet<String>()
			val enabledEntities = if (!allEnabled) entities.filter { it.isEnabled } else entities
			val sources = enabledEntities.toSources(skipNsfw, order).filter { info ->
				val source = info.mangaSource
				if (!showBroken && source.isBroken) return@filter false
				true
			}
			EnabledSourcesSnapshot(
				sources = sources,
				disabledNames = disabledNames,
				enabledNames = enabledNames,
				allEnabled = allEnabled,
			)
		}.onEach { snapshot ->
			traceBrowseSources {
				"core_snapshot sources=${snapshot.sources.size} enabledNames=${snapshot.enabledNames.size} " +
					"disabledNames=${snapshot.disabledNames.size} allEnabled=${snapshot.allEnabled}"
			}
		}
	}.flattenLatest()
		.combine(observeExternalSources()) { snapshot, external ->
			val list = ArrayList<ContentSourceInfo>()
			external.forEach {
				if (it.name !in snapshot.disabledNames) {
					list.add(ContentSourceInfo(it, isEnabled = true, isPinned = true))
				}
			}
			list.addAll(snapshot.sources)
			snapshot.copy(sources = list).also {
				traceBrowseSources { "external_stage external=${external.size} total=${it.sources.size}" }
			}
		}
		.combine(observeJsonSources()) { snapshot, jsonSources ->
			val list = ArrayList<ContentSourceInfo>()
			list.addAll(snapshot.sources)
			
			val existingNames = snapshot.sources.mapToSet { it.mangaSource.name }
			jsonSources.forEach { jsonSource ->
				if (jsonSource.name !in existingNames) {
					list.add(ContentSourceInfo(jsonSource, isEnabled = jsonSource.isEnabled, isPinned = jsonSource.isPinned))
				}
			}
			snapshot.copy(sources = list).also {
				traceBrowseSources { "json_stage json=${jsonSources.size} total=${it.sources.size}" }
			}
		}
		.combine(observeMihonSources()) { snapshot, mihonSources ->
			val list = ArrayList<ContentSourceInfo>()
			list.addAll(snapshot.sources)
			
			val existingNames = snapshot.sources.mapToSet { it.mangaSource.name }
			mihonSources.forEach { mihonSource ->
				val isVisible = if (snapshot.allEnabled) true else mihonSource.name in snapshot.enabledNames
				if (isVisible && mihonSource.name !in existingNames && mihonSource.name !in snapshot.disabledNames) {
					list.add(ContentSourceInfo(mihonSource, isEnabled = true, isPinned = false))
				}
			}
			snapshot.copy(sources = list).also {
				traceBrowseSources { "mihon_stage extensions=${mihonSources.size} total=${it.sources.size}" }
			}
		}
		.combine(observeAniyomiSources()) { snapshot, aniyomiSources ->
			val list = ArrayList<ContentSourceInfo>()
			list.addAll(snapshot.sources)
			
			val existingNames = snapshot.sources.mapToSet { it.mangaSource.name }
			aniyomiSources.forEach { aniyomiSource ->
				val isVisible = if (snapshot.allEnabled) true else aniyomiSource.name in snapshot.enabledNames
				if (isVisible && aniyomiSource.name !in existingNames && aniyomiSource.name !in snapshot.disabledNames) {
					list.add(ContentSourceInfo(aniyomiSource, isEnabled = true, isPinned = false))
				}
			}
			snapshot.copy(sources = list).also {
				traceBrowseSources { "aniyomi_stage extensions=${aniyomiSources.size} total=${it.sources.size}" }
			}
		}
		.combine(observeIReaderSources()) { snapshot, ireaderSources ->
			val list = ArrayList<ContentSourceInfo>()
			list.addAll(snapshot.sources)

			val existingNames = snapshot.sources.mapToSet { it.mangaSource.name }
			ireaderSources.forEach { ireaderSource ->
				val isVisible = if (snapshot.allEnabled) true else ireaderSource.name in snapshot.enabledNames
				if (isVisible && ireaderSource.name !in existingNames && ireaderSource.name !in snapshot.disabledNames) {
					list.add(ContentSourceInfo(ireaderSource, isEnabled = true, isPinned = false))
				}
			}
			canonicalizeSourceInfosByName(list).also {
				traceBrowseSources { "ireader_stage extensions=${ireaderSources.size} total=${it.size}" }
			}
		}
		.combine(sourceAvailabilityRepository.observeAvailability()) { sources, availability ->
			sources.map { info ->
				info.copy(
					availability = availability[info.mangaSource.name] ?: ContentSourceAvailability.UNKNOWN,
				)
			}.also {
				traceBrowseSources { "availability_stage entries=${availability.size} total=${it.size}" }
			}
		}

	/**
	 * 对齐 legado-with-MD3：浏览(发现)仅展示具备 exploreUrl 的源；仅提供 searchUrl 的源不应出现在浏览页。
	 *
	 * 说明：
	 * - 仅针对 JSON_LEGADO 源做该过滤（避免误伤 JS/TVBox 等其它 JSON 类型）。
	 * - 搜索仍使用 `getEnabledSources()`，不受影响。
	 */
	fun observeEnabledBrowseSources(): StateFlow<List<ContentSourceInfo>> = enabledBrowseSources

	private fun createEnabledBrowseSourcesFlow(): Flow<List<ContentSourceInfo>> {
		return combine(
			observeEnabledSources(),
			settings.observeAsFlow(AppSettings.KEY_EXPLORE_HIDE_EMPTY_SOURCES) { isEmptySourcesHiddenInExplore },
		) { sources, hideEmptySources ->
			if (hideEmptySources) {
				sources.filterNot { it.availability == ContentSourceAvailability.EMPTY }
			} else {
				sources
			}
		}
	}
	
	/**
	 * Observes all Aniyomi sources.
	 */
	private fun observeAniyomiSources(): Flow<List<org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource>> {
		return combine(
			aniyomiExtensionManager.installedExtensions,
			observeIsNsfwDisabled()
		) { _, _ ->
			getEnabledAniyomiSources()
		}
	}
	
	/**
	 * Observes all enabled JSON sources as ContentSource instances.
	 * 
	 * @return Flow emitting list of JSON sources wrapped as ContentSource
	 */
	private fun observeJsonSources(): Flow<List<JsonSourceListSource>> {
		return combine(
			jsonDao.observeEnabledSummaries(),
			observeIsNsfwDisabled(),
		) { entities, skipNsfw ->
			entities
				.map(::JsonSourceListSource)
				.filter { source -> !skipNsfw || !source.isNsfw() }
		}
	}
	
	/**
	 * Observes all Mihon sources.
	 * 
	 * @return Flow emitting list of Mihon sources
	 */
	private fun observeMihonSources(): Flow<List<org.skepsun.kototoro.mihon.model.MihonMangaSource>> {
		return combine(
			mihonExtensionManager.installedExtensions,
			observeIsNsfwDisabled()
		) { _, _ ->
			getEnabledMihonSources()
		}
	}

	fun observeAll(): Flow<List<Pair<ContentSource, Boolean>>> = dao.observeAll().map { entities ->
		val result = ArrayList<Pair<ContentSource, Boolean>>(entities.size)
		for (entity in entities) {
			val source = entity.source.toContentSourceOrNull() ?: continue
			if (source in allContentSources) {
				result.add(source to entity.isEnabled)
			}
		}
		result
	}.onStart { assimilateNewSources() }

	suspend fun setSourcesEnabled(sources: Collection<ContentSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<ContentSource>) {
		val allSources = queryAllSources(includeDisabledSources = true)
		val enabledNames = sources.map { it.name }.toSet()
		
		val jsonSourcesToEnable = mutableListOf<String>()
		val jsonSourcesToDisable = mutableListOf<String>()
		val nativeSourcesToEnable = mutableListOf<String>()
		val nativeSourcesToDisable = mutableListOf<String>()
		
		for (s in allSources) {
			val isEnabled = s.name in enabledNames
			if (s.name.startsWith("JSON_")) {
				if (isEnabled) jsonSourcesToEnable.add(s.name) else jsonSourcesToDisable.add(s.name)
			} else {
				if (isEnabled) nativeSourcesToEnable.add(s.name) else nativeSourcesToDisable.add(s.name)
			}
		}
		
		if (jsonSourcesToEnable.isNotEmpty()) jsonSourceManager.toggleSourcesBatch(jsonSourcesToEnable, true)
		if (jsonSourcesToDisable.isNotEmpty()) jsonSourceManager.toggleSourcesBatch(jsonSourcesToDisable, false)
		
		db.withTransaction {
			assimilateNewSources()
			for (name in nativeSourcesToEnable) dao.setEnabled(name, true)
			for (name in nativeSourcesToDisable) dao.setEnabled(name, false)
		}
		settings.isAllSourcesEnabled = false
	}

	suspend fun disableAllSources() {
		val currentEnabled = getEnabledSources()
		setSourcesEnabledImpl(currentEnabled, false)
		settings.isAllSourcesEnabled = false
	}

	suspend fun setPositions(sources: List<ContentSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(skipNsfw, null)
		sources.isNotEmpty() && sources.size != allContentSources.size
	}.onStart { assimilateNewSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
	) { version, skipNsfw ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(skipNsfw, null)
			sources.isNotEmpty()
		} else {
			false
		}
	}.onStart {
		emit(false)
		assimilateNewSources()
	}.distinctUntilChanged()

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	private suspend fun assimilateNewSources(force: Boolean = false): Boolean {
		if (!force && isNewSourcesAssimilated.getAndSet(true)) {
			traceBrowseSources { "assimilate skipped force=false alreadyCompleted=true" }
			return false
		}
		val startedAt = SystemClock.elapsedRealtime()
		traceBrowseSources { "assimilate started force=$force" }
		isNewSourcesAssimilated.set(true)
		val new = getNewSources()
		if (new.isEmpty()) {
			traceBrowseSources {
				"assimilate completed force=$force new=0 durationMs=${SystemClock.elapsedRealtime() - startedAt}"
			}
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		traceBrowseSources {
			"assimilate completed force=$force new=${entities.size} durationMs=${SystemClock.elapsedRealtime() - startedAt}"
		}
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return !settings.hasSeenPluginWelcome || (settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty())
	}

	suspend fun setIsPinned(sources: Collection<ContentSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesPinnedImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: ContentSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<ContentSource>, isEnabled: Boolean) {
		if (!isEnabled && settings.isAllSourcesEnabled) {
			materializeAllEnabledState()
		}

		val nativeSources = mutableListOf<String>()
		val jsonSources = mutableListOf<String>()
		for (source in sources) {
			if (source.name.startsWith("JSON_")) {
				jsonSources.add(source.name)
			} else {
				nativeSources.add(source.name)
			}
		}

		if (jsonSources.isNotEmpty()) {
			jsonSourceManager.toggleSourcesBatch(jsonSources, isEnabled)
		}

		if (nativeSources.isNotEmpty()) {
			db.withTransaction {
				for (name in nativeSources) {
					dao.setEnabled(name, isEnabled)
				}
			}
		}
	}

	private suspend fun materializeAllEnabledState() {
		val allSources = queryAllSources(includeDisabledSources = true)
		val nativeSources = mutableListOf<String>()
		val jsonSources = mutableListOf<String>()
		for (source in allSources) {
			if (source.name.startsWith("JSON_")) {
				jsonSources.add(source.name)
			} else {
				nativeSources.add(source.name)
			}
		}

		if (jsonSources.isNotEmpty()) {
			jsonSourceManager.toggleSourcesBatch(jsonSources, true)
		}

		if (nativeSources.isNotEmpty()) {
			db.withTransaction {
				assimilateNewSources()
				for (name in nativeSources) {
					dao.setEnabled(name, true)
				}
			}
		}

		settings.isAllSourcesEnabled = false
	}

	private suspend fun normalizeAllEnabledFlagIfNeeded() {
		if (!settings.isAllSourcesEnabled) {
			return
		}
		if (dao.findAll().any { !it.isEnabled }) {
			settings.isAllSourcesEnabled = false
		}
	}

	private suspend fun getNewSources(): MutableSet<out ContentSource> {
		val entities = dao.findAll()
		val existing = entities.mapToSet { it.source }
		val result = LinkedHashSet<ContentSource>()
		val totalSources = buildList {
			addAll(allContentSources)
			addAll(getEnabledMihonSources())
			addAll(getEnabledAniyomiSources())
			addAll(getEnabledIReaderSources())
		}
		for (source in totalSources) {
			if (source.name !in existing) {
				result.add(source)
			}
		}
		return result
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<ContentSource>, isPinned: Boolean) {
		val nativeSources = mutableListOf<String>()
		val jsonSources = mutableListOf<String>()
		for (source in sources) {
			if (source.name.startsWith("JSON_")) {
				jsonSources.add(source.name)
			} else {
				nativeSources.add(source.name)
			}
		}

		if (jsonSources.isNotEmpty()) {
			jsonSourceManager.setSourcesPinnedBatch(jsonSources, isPinned)
		}

		if (nativeSources.isNotEmpty()) {
			db.withTransaction {
				for (name in nativeSources) {
					dao.setPinned(name, isPinned)
				}
			}
		}
	}

	/**
	 * Gets the source type label for a given source.
	 * This is useful for displaying the source type in the UI.
	 * 
	 * @param source The manga source
	 * @return A human-readable label for the source type
	 */
	fun getSourceTypeLabel(source: ContentSource): String {
		return sourceTypeIdentifier.getSourceTypeLabel(source.name)
	}
	
	/**
	 * Observes sources grouped by content type.
	 * 
	 * @param contentGroup The content group to filter by
	 * @return Flow emitting list of sources in the specified content group
	 */
	fun observeSourcesByContentGroup(
		contentGroup: org.skepsun.kototoro.core.jsonsource.ContentGroup
	): Flow<List<ContentSourceInfo>> {
		return observeEnabledSources().map { sources ->
			sources.filter { sourceInfo ->
				sourceGroupManager.getContentGroup(sourceInfo.mangaSource) == contentGroup
			}
		}
	}
	
	/**
	 * Observes sources grouped by origin type.
	 * 
	 * @param originGroup The origin group to filter by
	 * @return Flow emitting list of sources in the specified origin group
	 */
	fun observeSourcesByOriginGroup(
		originGroup: org.skepsun.kototoro.core.jsonsource.OriginGroup
	): Flow<List<ContentSourceInfo>> {
		return observeEnabledSources().map { sources ->
			sources.filter { sourceInfo ->
				sourceGroupManager.getOriginGroup(sourceInfo.mangaSource) == originGroup
			}
		}
	}
	
	/**
	 * Observes counts of sources in each group.
	 * 
	 * @return Flow emitting map of SourceGroup to count
	 */
	fun observeGroupCounts(): Flow<Map<org.skepsun.kototoro.core.jsonsource.SourceGroup, Int>> {
		return observeEnabledSources().map { sources ->
			val mangaSources = sources.map { it.mangaSource }
			sourceGroupManager.getGroupCounts(mangaSources)
		}
	}
	
	/**
	 * Checks if a source is a JSON source.
	 * 
	 * @param source The manga source
	 * @return true if the source is a JSON source
	 */
	fun isJsonSource(source: ContentSource): Boolean {
		return sourceTypeIdentifier.isJsonSource(source.name)
	}
	
	/**
	 * Gets the source type for a given source.
	 * 
	 * @param source The manga source
	 * @return The SourceType enum value
	 */
	fun getSourceType(source: ContentSource): org.skepsun.kototoro.core.jsonsource.SourceType {
		return sourceTypeIdentifier.getSourceType(source.name)
	}
	
	private fun observeExternalSources(): Flow<List<ExternalContentSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalContentSource> = context.packageManager.queryIntentContentProviders(
		Intent("app.kototoro.parser.PROVIDE_MANGA"), 0,
	).map { resolveInfo ->
		ExternalContentSource(
			packageName = resolveInfo.providerInfo.packageName,
			authority = resolveInfo.providerInfo.authority,
		)
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
	): MutableList<ContentSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<ContentSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toContentSourceOrNull() ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			// Allow native sources, Mihon sources, and JSON sources
			val isKnownSource = source in allContentSources || 
								source is org.skepsun.kototoro.mihon.model.MihonMangaSource ||
								source is org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource ||
								source is org.skepsun.kototoro.ireader.model.IReaderMangaSource ||
								source is org.skepsun.kototoro.core.jsonsource.JsonContentSource
								
			if (isKnownSource) {
				result.add(
					ContentSourceInfo(
						mangaSource = source,
						isEnabled = (entity.isEnabled || isAllEnabled),
						isPinned = entity.isPinned,
					),
				)
			}
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<ContentSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}.onStart {
		normalizeAllEnabledFlagIfNeeded()
	}

	private fun String.toContentSourceOrNull(allowFallback: Boolean = true): ContentSource? {
		// Try Global Registry for PluginContentSources first
		org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources.value.find { it.name == this }?.let { return it }
		org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources.value.find { it.name == this }?.let { 
			return cachedKotatsuSources.getOrPut(it.name) { org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource(it) } 
		}
		cloudstreamRuntimeManager.findSourceByName(this)?.let { return it }

		// Try Mihon sources
		if (startsWith("MIHON_")) {
			mihonExtensionManager.getMihonMangaSources().find { it.name == this }?.let { return it }
		}
		
		// Try Aniyomi sources
		if (startsWith("ANIYOMI_")) {
			aniyomiExtensionManager.installedExtensions.value.flatMap { ext ->
				ext.catalogueSources.map { catalogueSource ->
					org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource(
						animeCatalogueSource = catalogueSource,
						pkgName = ext.pkgName,
						isNsfw = ext.isNsfw
					)
				}
			}.find { it.name == this }?.let { return it }
		}
		
		// Try IReader sources
		if (startsWith("IREADER_")) {
			ireaderExtensionManager.getIReaderMangaSources().find { it.name == this }?.let { return it }
		}
		
		// Try JSON sources
		if (startsWith("JSON_")) {
			// This is a bit expensive but necessary for pinning/top sources to work correctly
			val jsonSources = kotlinx.coroutines.runBlocking { 
				jsonSourceManager.observeAllJsonSources().first().map {
					org.skepsun.kototoro.core.jsonsource.JsonContentSource(it)
				}
			}
			jsonSources.find { it.name == this }?.let { return it }
		}

		if (!allowFallback) return null
		// Fallback to anonymous/static wrapper
		val fallback = org.skepsun.kototoro.core.model.ContentSource(this)
		return if (fallback == org.skepsun.kototoro.core.model.UnknownContentSource) null else fallback
	}

	fun isSourceAvailable(sourceName: String): Boolean = sourceName.toContentSourceOrNull(allowFallback = false) != null

	private fun canonicalizeSourcesByName(sources: List<ContentSource>): List<ContentSource> {
		if (sources.size <= 1) {
			return sources
		}
		return LinkedHashMap<String, ContentSource>(sources.size).apply {
			sources.forEach { source ->
				putIfAbsent(source.name, source)
			}
		}.values.toList()
	}

	private fun canonicalizeSourceInfosByName(sources: List<ContentSourceInfo>): List<ContentSourceInfo> {
		if (sources.size <= 1) {
			return sources
		}
		return LinkedHashMap<String, ContentSourceInfo>(sources.size).apply {
			sources.forEach { source ->
				putIfAbsent(source.mangaSource.name, source)
			}
		}.values.toList()
	}
}
