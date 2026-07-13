package org.skepsun.kototoro.core.jsonsource

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.js.JSSourceParser
import org.skepsun.kototoro.core.lnreader.LNReaderPluginMetadata
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.javascript.JavaScriptEnginePool
import org.skepsun.kototoro.core.parser.legado.book.BookChapterList
import org.skepsun.kototoro.core.parser.legado.book.BookContent
import org.skepsun.kototoro.core.parser.legado.book.BookInfo
import org.skepsun.kototoro.core.parser.legado.book.BookList
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import java.net.IDN
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages JSON sources including import, storage, and retrieval operations.
 * 
 * This class handles the lifecycle of JSON-based manga sources, including:
 * - Generating unique identifiers for sources
 * - Importing and validating JSON configurations
 * - Managing source state (enabled/disabled)
 * - Tracking source usage
 * 
 * Performance optimizations:
 * - Async processing with coroutines for concurrent operations
 * - Batch database operations for efficiency
 * - Query result caching for frequently accessed data
 */
@Singleton
class JsonSourceManager @Inject constructor(
	private val jsonSourceDao: JsonSourceDao,
	private val appSettings: AppSettings,
	private val legadoHttpClient: LegadoHttpClient? = null,
	private val jsSourceParser: JSSourceParser? = null,
	private val jsEnginePool: JavaScriptEnginePool? = null,
) {
	
	/**
	 * JSON serializer configured for lenient parsing of JSON sources.
	 * - ignoreUnknownKeys: Allows parsing JSON with extra fields not defined in the model
	 * - isLenient: Allows non-strict JSON parsing (e.g., unquoted keys, trailing commas)
	 */
	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}
	
	/**
	 * Cache for frequently accessed sources
	 * This reduces database queries for commonly used sources
	 */
	private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, JsonSourceEntity>()
	
	companion object {
		private const val JSON_PREFIX = "JSON_"
		private const val LEGADO_PREFIX = "JSON_LEGADO_"
		private const val LEGADO_MANGA_PREFIX = "JSON_LEGADO_M_"
		private const val TVBOX_PREFIX = "JSON_TVBOX_"
		private const val JS_PREFIX = "JSON_JS_"
		private const val LNREADER_PREFIX = "JSON_LNREADER_"
		private const val MAX_TVBOX_REPOSITORY_DEPTH = 3
		
		// Regex pattern to match valid identifier characters (alphanumeric and underscore)
		private val VALID_CHAR_REGEX = Regex("[^A-Z0-9_]")
		private val TVBOX_URL_REGEX = Regex("^(https?)://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE)
		private val MAC_CMS_API_PATHS = listOf(
			"/api.php/provide/vod",
			"/index.php/api/vod",
			"/api/vod",
			"/provide/vod",
		)
	}
	
	/**
	 * Observes all JSON sources in the database.
	 * 
	 * @return Flow emitting list of all JSON sources
	 */
	fun observeAllJsonSources(): Flow<List<JsonSourceEntity>> {
		return jsonSourceDao.observeAll()
	}
	
	/**
	 * Observes only enabled JSON sources.
	 * 
	 * @return Flow emitting list of enabled JSON sources
	 */
	fun observeEnabledJsonSources(): Flow<List<JsonSourceEntity>> {
		return jsonSourceDao.observeEnabled()
	}
	
	/**
	 * Gets a JSON source by its ID with caching.
	 * 
	 * This method checks the cache first before querying the database,
	 * improving performance for frequently accessed sources.
	 * 
	 * @param sourceId The source identifier
	 * @return The JsonSourceEntity if found, null otherwise
	 */
	suspend fun getById(sourceId: String): JsonSourceEntity? {
		// Check cache first
		sourceCache[sourceId]?.let { return it }
		
		// Query database if not in cache
		val source = jsonSourceDao.getById(sourceId)
		
		// Cache the result if found
		source?.let { sourceCache[sourceId] = it }
		
		return source
	}
	
	/**
	 * Gets multiple sources by IDs (batch query)
	 * More efficient than multiple individual queries
	 * 
	 * @param sourceIds List of source identifiers
	 * @return List of found sources
	 */
	suspend fun getByIds(sourceIds: List<String>): List<JsonSourceEntity> {
		// Check which sources are already in cache
		val cached = mutableListOf<JsonSourceEntity>()
		val uncachedIds = mutableListOf<String>()
		
		sourceIds.forEach { id ->
			val cachedSource = sourceCache[id]
			if (cachedSource != null) {
				cached.add(cachedSource)
			} else {
				uncachedIds.add(id)
			}
		}
		
		// Query database for uncached sources
		val fromDb = if (uncachedIds.isNotEmpty()) {
			jsonSourceDao.getByIds(uncachedIds).also { sources ->
				// Cache the results
				sources.forEach { sourceCache[it.id] = it }
			}
		} else {
			emptyList()
		}
		
		return cached + fromDb
	}
	
	/**
	 * Invalidate cache for a specific source
	 * Should be called when a source is updated or deleted
	 */
	private fun invalidateCache(sourceId: String) {
		sourceCache.remove(sourceId)
	}
	
	/**
	 * Clear the entire source cache
	 * Useful when performing bulk operations
	 */
	fun clearCache() {
		sourceCache.clear()
	}
	
	/**
	 * Toggles the enabled state of a JSON source.
	 * 
	 * @param sourceId The source identifier
	 * @param enabled Whether the source should be enabled
	 */
	suspend fun toggleSource(sourceId: String, enabled: Boolean) {
		try {
			val timestamp = System.currentTimeMillis()
			jsonSourceDao.setEnabled(sourceId, enabled, timestamp)
			invalidateCache(sourceId)
			JsonSourceLogger.logStateChange(sourceId, enabled)
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("toggle source state", e)
			throw JsonSourceError.DatabaseError("toggle source state", e)
		}
	}
	
	/**
	 * Batch toggle multiple sources
	 * More efficient than toggling sources individually
	 * 
	 * @param sourceIds List of source identifiers
	 * @param enabled Whether the sources should be enabled
	 */
	suspend fun toggleSourcesBatch(sourceIds: List<String>, enabled: Boolean) {
		try {
			val timestamp = System.currentTimeMillis()
			jsonSourceDao.setEnabledBatch(sourceIds, enabled, timestamp)
			sourceIds.forEach { invalidateCache(it) }
			JsonSourceLogger.logInfo("Batch toggled ${sourceIds.size} sources to enabled=$enabled")
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("batch toggle sources", e)
			throw JsonSourceError.DatabaseError("batch toggle sources", e)
		}
	}

	suspend fun setSourcePinned(sourceId: String, isPinned: Boolean) {
		try {
			val timestamp = System.currentTimeMillis()
			jsonSourceDao.setPinned(sourceId, isPinned, timestamp)
			invalidateCache(sourceId)
			JsonSourceLogger.logStateChange(sourceId, isPinned)
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("set source pinned", e)
		}
	}

	suspend fun setSourcesPinnedBatch(sourceIds: List<String>, isPinned: Boolean) {
		try {
			val timestamp = System.currentTimeMillis()
			jsonSourceDao.setPinnedBatch(sourceIds, isPinned, timestamp)
			sourceIds.forEach { invalidateCache(it) }
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("batch set sources pinned", e)
		}
	}

	/**
	 * Deletes a JSON source from the database.
	 * 
	 * @param sourceId The source identifier
	 */
	suspend fun deleteSource(sourceId: String) {
		try {
			jsonSourceDao.deleteById(sourceId)
			invalidateCache(sourceId)
			JsonSourceLogger.logDeletion(sourceId)
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("delete source", e)
			throw JsonSourceError.DatabaseError("delete source", e)
		}
	}
	
	/**
	 * Batch delete multiple sources
	 * More efficient than deleting sources individually
	 * 
	 * @param sourceIds List of source identifiers
	 */
	suspend fun deleteSourcesBatch(sourceIds: List<String>) {
		try {
			jsonSourceDao.deleteByIds(sourceIds)
			sourceIds.forEach { invalidateCache(it) }
			JsonSourceLogger.logInfo("Batch deleted ${sourceIds.size} sources")
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("batch delete sources", e)
			throw JsonSourceError.DatabaseError("batch delete sources", e)
		}
	}
	
	/**
	 * Updates the last used timestamp for a source.
	 * 
	 * @param sourceId The source identifier
	 */
	suspend fun trackUsage(sourceId: String) {
		try {
			val timestamp = System.currentTimeMillis()
			jsonSourceDao.setLastUsed(sourceId, timestamp)
			JsonSourceLogger.logUsageTracking(sourceId)
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("track source usage", e)
			// Don't throw here - usage tracking is not critical
			// Just log the error and continue
		}
	}

	suspend fun fillMissingIconUrl(sourceId: String, iconUrl: String, timestamp: Long = System.currentTimeMillis()) {
		if (iconUrl.isBlank()) return
		jsonSourceDao.fillMissingIconUrl(sourceId, iconUrl, timestamp)
		invalidateCache(sourceId)
	}
	
	/**
	 * Inserts a new JSON source into the database.
	 * 
	 * @param entity The new source entity
	 */
	suspend fun insertSource(entity: JsonSourceEntity) {
		try {
			jsonSourceDao.insert(entity)
			invalidateCache(entity.id)
			JsonSourceLogger.logInfo("Inserted source: ${entity.name}")
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("insert source", e)
			throw JsonSourceError.DatabaseError("insert source", e)
		}
	}

	/**
	 * Updates a JSON source in the database.
	 * 
	 * @param entity The updated source entity
	 */
	suspend fun updateSource(entity: JsonSourceEntity) {
		try {
			jsonSourceDao.update(entity)
			invalidateCache(entity.id)
			JsonSourceLogger.logInfo("Updated source: ${entity.name}")
		} catch (e: Exception) {
			JsonSourceLogger.logDatabaseError("update source", e)
			throw JsonSourceError.DatabaseError("update source", e)
		}
	}
	
	/**
	 * Validate a source by performing a multi-stage check:
	 * Search -> Details -> Chapter List -> Content
	 * This aligns with Legado's source verification logic.
	 */
	suspend fun validateSourceBySearch(sourceId: String, searchKey: String = "我的"): Boolean {
		val client = legadoHttpClient ?: return false
		val enginePool = jsEnginePool ?: return false
		val entity = getById(sourceId) ?: return false
		val config = runCatching {
			Json { ignoreUnknownKeys = true; isLenient = true; allowTrailingComma = true }
				.decodeFromString<org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource>(entity.config)
		}.getOrNull() ?: return false
		
		val searchUrl = config.searchUrl
		val exploreUrl = config.exploreUrl
		if (searchUrl.isNullOrBlank() && exploreUrl.isNullOrBlank()) return false
		
		val engine = enginePool.acquire()
		return try {
			val sandbox = LegadoSandbox(engine, client, config)
			val mangaSource = JsonContentSource(entity)
			var results: List<org.skepsun.kototoro.parsers.model.Content> = emptyList()
			
			// 1. Try Search if available
			if (!searchUrl.isNullOrBlank()) {
				val page = 1
				val encodedKey = java.net.URLEncoder.encode(searchKey, "UTF-8")
				val finalSearchUrl = searchUrl
					.replace("{{key}}", encodedKey)
					.replace("{key}", encodedKey)
					.replace("{{page}}", page.toString())
					.replace("{page}", page.toString())
				
				val searchResponse = client.get(finalSearchUrl, parseCustomHeaders(config.header), source = null)
				val searchBody = searchResponse.body?.string().orEmpty()
				searchResponse.close()
				
				results = BookList.parse(searchBody, finalSearchUrl, mangaSource, config, true, sandbox)
			}
			
			// 2. Try Explore fallback if search failed or not available
			if (results.isEmpty() && !exploreUrl.isNullOrBlank()) {
				try {
					val firstExploreUrl = if (exploreUrl.trim().startsWith("[")) {
						org.json.JSONArray(exploreUrl).optJSONObject(0)?.optString("url")
					} else {
						exploreUrl.split("&&").first().split("\n").first().trim()
					}
					
					if (!firstExploreUrl.isNullOrBlank()) {
						val fullExploreUrl = if (firstExploreUrl.startsWith("http")) {
							firstExploreUrl
						} else {
							val baseUrl = config.bookSourceUrl.removeSuffix("/")
							if (firstExploreUrl.startsWith("/")) "$baseUrl$firstExploreUrl" else "$baseUrl/$firstExploreUrl"
						}
						
						val exploreResponse = client.get(fullExploreUrl, parseCustomHeaders(config.header), source = null)
						val exploreBody = exploreResponse.body?.string().orEmpty()
						exploreResponse.close()
						
						results = BookList.parse(exploreBody, fullExploreUrl, mangaSource, config, false, sandbox)
					}
				} catch (e: Exception) {
					JsonSourceLogger.logWarning("Explore fallback failed during validation: ${e.message}")
				}
			}
			
			if (results.isEmpty()) return false
			val firstContent = results.first()
			
			// 2. Details
			val detailsResponse = client.get(firstContent.url, parseCustomHeaders(config.header), source = null)
			val detailsBody = detailsResponse.body?.string().orEmpty()
			detailsResponse.close()
			
			val infoResult = BookInfo.parse(firstContent, detailsBody, firstContent.url, config, sandbox)
			
			// 3. Chapter List (TOC)
			val tocUrl = infoResult.tocUrl ?: firstContent.url
			val tocResponse = client.get(tocUrl, parseCustomHeaders(config.header), source = null)
			val tocBody = tocResponse.body?.string().orEmpty()
			tocResponse.close()
			
			val tocResult = BookChapterList.parse(tocBody, tocUrl, mangaSource, config, sandbox)
			val chapters = tocResult.chapters
			if (chapters.isEmpty()) return false
			
			// 4. Content
			val firstChapter = chapters.first()
			val contentResponse = client.get(firstChapter.url, parseCustomHeaders(config.header), source = null)
			val contentBody = contentResponse.body?.string().orEmpty()
			contentResponse.close()
			
			val content = BookContent.parse(contentBody, firstChapter.url, mangaSource, config, sandbox)
			
			content.pages.isNotEmpty()
		} catch (e: Exception) {
			JsonSourceLogger.logError("Verification failed for ${entity.name}", e)
			false
		} finally {
			enginePool.release(engine)
		}
	}
	
	/**
	 * Imports a single Venera-style JavaScript source.
	 */
	suspend fun importJsSource(jsContent: String): Result<Int> {
		val parser = jsSourceParser ?: return Result.failure(IllegalStateException("JS parser unavailable"))
		
		return parser.parseMetadata(jsContent).mapCatching { meta ->
			val base = meta.homepage?.takeIf { it.isNotBlank() } ?: meta.key
			val sourceId = runCatching { generateSourceId(base, JsonSourceType.JS) }
				.getOrElse { generateSourceIdFromName(meta.key, JsonSourceType.JS) }
			
			val timestamp = System.currentTimeMillis()
			val entity = JsonSourceEntity(
				id = sourceId,
				name = meta.name,
				type = JsonSourceType.JS,
				config = jsContent,
				enabled = true,
				createdAt = timestamp,
				updatedAt = timestamp,
				lastUsedAt = 0,
				isPinned = false,
				iconUrl = deriveFaviconUrl(meta.homepage),
			)
			
			jsonSourceDao.insert(entity)
			
			runCatching { parser.saveSource(jsContent, "$sourceId.js") }
			
			1
		}
	}
	
	/**
	 * Imports a LNReader JS plugin.
	 * Extracts metadata using regex (no JS engine needed for import),
	 * stores the full JS bundle in the config field.
	 */
	suspend fun importLNReaderPlugin(
		jsContent: String,
		metadataOverride: LNReaderPluginMetadata? = null,
	): Result<Int> {
		return try {
			val fallbackId = "lnreader_${System.currentTimeMillis()}"
			val extractedMeta = LNReaderPluginMetadata.extractFromCode(jsContent, fallbackId)
				?: return Result.failure(IllegalArgumentException("Cannot extract plugin metadata from JS code"))
			val meta = metadataOverride
				?.mergeMissing(extractedMeta)
				?.sanitized()
				?: extractedMeta
			
			val sourceId = generateSourceId(meta.site.ifBlank { meta.id }, JsonSourceType.LNREADER)
			
			val timestamp = System.currentTimeMillis()
			val entity = JsonSourceEntity(
				id = sourceId,
				name = meta.name,
				type = JsonSourceType.LNREADER,
				config = jsContent,
				enabled = true,
				createdAt = timestamp,
				updatedAt = timestamp,
				lastUsedAt = 0,
				isPinned = false,
				iconUrl = normalizeLnReaderIconUrl(meta.icon),
			)
			
			jsonSourceDao.insert(entity)
			JsonSourceLogger.logInfo("Imported LNReader plugin: ${meta.name} (${meta.id})")
			
			Result.success(1)
		} catch (e: Exception) {
			JsonSourceLogger.logError("Failed to import LNReader plugin", e)
			Result.failure(e)
		}
	}

	private fun LNReaderPluginMetadata.mergeMissing(fallback: LNReaderPluginMetadata): LNReaderPluginMetadata {
		return LNReaderPluginMetadata(
			id = id.ifBlank { fallback.id },
			name = name.ifBlank { fallback.name },
			site = site.ifBlank { fallback.site },
			version = version.ifBlank { fallback.version },
			lang = lang.ifBlank { fallback.lang },
			icon = icon.ifBlank { fallback.icon },
		)
	}

	private fun normalizeLnReaderIconUrl(icon: String?): String? {
		val value = icon?.trim()?.takeIf { it.isNotBlank() } ?: return null
		if (value.startsWith("http://") || value.startsWith("https://")) return value
		return value
			.removePrefix("/")
			.takeIf { it.startsWith("src/") || it.startsWith("multisrc/") }
			?.let { "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/public/static/$it" }
	}
	
	/**
	 * Imports Legado JSON configuration with async processing.
	 * 
	 * This method:
	 * 1. Parses the JSON content into a list of LegadoBookSource objects
	 * 2. Validates each source's required fields (in parallel using coroutines)
	 * 3. Generates unique identifiers for each source
	 * 4. Batch inserts all valid sources into the database
	 * 
	 * Performance optimizations:
	 * - Uses coroutines for concurrent validation and processing
	 * - Batch database operations for efficiency
	 * - Async loading reduces blocking time
	 * 
	 * @param jsonContent The JSON string containing Legado book sources
	 * @return Result containing the number of successfully imported sources or error message
	 */
	suspend fun importLegadoJson(
		jsonContent: String,
		skipUnreachable: Boolean = false,
		skipNoExplore: Boolean = false,
		sourceLocator: String? = null,
		sourceTitle: String? = null,
	): Result<Int> {
		val startTime = System.currentTimeMillis()
		JsonSourceLogger.logImportStart("LEGADO", jsonContent.length)
		
		return try {
			// Parse JSON array into list of LegadoBookSource objects
			val sources = json.decodeFromString<List<org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource>>(jsonContent)
			
			if (sources.isEmpty()) {
				JsonSourceLogger.logWarning("JSON array is empty")
				return Result.failure(IllegalArgumentException("JSON array is empty"))
			}
			
			JsonSourceLogger.logInfo("Parsing ${sources.size} source(s) from JSON")
			
			// Process sources concurrently using coroutines
			val entities = mutableListOf<JsonSourceEntity>()
			val errors = mutableListOf<String>()
			
			// Use withContext to ensure we're on the IO dispatcher
			kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
				// Process sources sequentially for now
				// TODO: Re-enable parallel processing once Kotlin coroutines API stabilizes
				val results = sources.mapIndexed { index, source ->
					processSource(
						index = index,
						source = source,
						skipUnreachable = skipUnreachable,
						skipNoExplore = skipNoExplore,
						sourceLocator = sourceLocator,
						sourceTitle = sourceTitle,
					)
				}
				
				// Collect results
				results.forEach { result ->
					when (result) {
						is SourceProcessResult.Success -> entities.add(result.entity)
						is SourceProcessResult.Error -> errors.add(result.message)
					}
				}
			}
			
			// Log validation results
			JsonSourceLogger.logInfo("Validation complete: ${entities.size} valid, ${errors.size} invalid out of ${sources.size} total")
			
			// If all sources failed validation, return error
			if (entities.isEmpty()) {
				val errorMsg = "No valid sources found. All ${sources.size} sources failed validation. Errors:\n${errors.joinToString("\n")}"
				JsonSourceLogger.logWarning(errorMsg)
				return Result.failure(IllegalArgumentException(errorMsg))
			}
			
			// Log any validation errors for individual sources
			if (errors.isNotEmpty()) {
				JsonSourceLogger.logWarning("${errors.size} source(s) failed validation:\n${errors.joinToString("\n")}")
			}
			
			// Batch insert all valid sources
			try {
				jsonSourceDao.insertAll(entities)
				JsonSourceLogger.logDebug("Batch inserted ${entities.size} source(s) into database")
			} catch (e: Exception) {
				JsonSourceLogger.logDatabaseError("batch insert sources", e)
				throw JsonSourceError.DatabaseError("batch insert sources", e)
			}
			
			// Return success with count (and warnings if some failed)
			val successCount = entities.size
			val duration = System.currentTimeMillis() - startTime
			JsonSourceLogger.logImportSuccess("LEGADO", successCount, duration)
			
			if (errors.isNotEmpty()) {
				// Some sources failed but others succeeded
				JsonSourceLogger.logWarning("${errors.size} source(s) failed validation but $successCount succeeded")
				return Result.success(successCount)
			}
			
			Result.success(successCount)
		} catch (e: kotlinx.serialization.SerializationException) {
			JsonSourceLogger.logImportError("LEGADO", e)
			Result.failure(IllegalArgumentException("Invalid JSON format: ${e.message}", e))
		} catch (e: JsonSourceError) {
			JsonSourceLogger.logImportError("LEGADO", e)
			Result.failure(e)
		} catch (e: Exception) {
			JsonSourceLogger.logImportError("LEGADO", e)
			Result.failure(e)
		}
	}

	/**
	 * Imports TVBox JSON.
	 *
	 * Supported shapes:
	 * - repository JSON with `sites`
	 * - multi-repository JSON with `urls`
	 */
	suspend fun importTvBoxJson(
		jsonContent: String,
		sourceLocator: String? = null,
		sourceTitle: String? = null,
	): Result<Int> {
		val startTime = System.currentTimeMillis()
		JsonSourceLogger.logImportStart("TVBOX", jsonContent.length)

		return try {
			val entities = mutableListOf<JsonSourceEntity>()
			val visitedUrls = linkedSetOf<String>()
			val errors = mutableListOf<String>()
			val normalizedInput = jsonContent.trim()
			val directMacCmsUrl = detectMacCmsApiUrl(sourceLocator.orEmpty())
				?: detectMacCmsApiUrl(normalizedInput)
				?: detectMacCmsPayloadApiUrl(normalizedInput, sourceLocator)
			val contentToProcess = if (directMacCmsUrl != null) {
				buildMacCmsTvBoxDocument(
					apiUrl = directMacCmsUrl,
					sourceTitle = sourceTitle,
				)
			} else {
				jsonContent
			}
			val effectiveSourceLocator = directMacCmsUrl ?: sourceLocator
			val effectiveSourceTitle = sourceTitle
				?: directMacCmsUrl?.let { buildTvBoxRepositoryTitle(it, null) }

			processTvBoxDocument(
				rawContent = contentToProcess,
				sourceLocator = effectiveSourceLocator,
				sourceTitle = effectiveSourceTitle,
				depth = 0,
				visitedUrls = visitedUrls,
				entities = entities,
				errors = errors,
			)

			if (entities.isEmpty()) {
				val message = if (errors.isNotEmpty()) {
					"No valid TVBox sites found. Errors:\n${errors.joinToString("\n")}"
				} else {
					"No valid TVBox sites found"
				}
				return Result.failure(IllegalArgumentException(message))
			}

			jsonSourceDao.insertAll(entities)
			val duration = System.currentTimeMillis() - startTime
			JsonSourceLogger.logImportSuccess("TVBOX", entities.size, duration)
			if (errors.isNotEmpty()) {
				JsonSourceLogger.logWarning("TVBox import completed with ${errors.size} warning(s):\n${errors.joinToString("\n")}")
			}
			Result.success(entities.size)
		} catch (e: Exception) {
			JsonSourceLogger.logImportError("TVBOX", e)
			Result.failure(e)
		}
	}
	
	/**
	 * Process a single source (validation, ID generation, entity creation)
	 * This is extracted to support concurrent processing
	 */
	private suspend fun processSource(
		index: Int,
		source: org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource,
		skipUnreachable: Boolean,
		skipNoExplore: Boolean,
		sourceLocator: String?,
		sourceTitle: String?,
	): SourceProcessResult {
		return try {
			// Validate the source
			val validation = validateLegadoSource(source)
			if (!validation.isValid) {
				JsonSourceLogger.logValidationError(source.bookSourceName, validation.errors)
				return SourceProcessResult.Error(
					"Source ${index + 1} (${source.bookSourceName}): ${validation.errors.joinToString(", ")}"
				)
			}
			
			// Basic connectivity check: fetch homepage to ensure reachable HTML
			if (skipUnreachable) {
				val homeCheck = runCatching { verifyHomePageAccessible(source) }.getOrElse { throwable ->
					return SourceProcessResult.Error("Source ${index + 1} (${source.bookSourceName}): homepage check failed - ${throwable.message}")
				}
				if (!homeCheck) {
					return SourceProcessResult.Error("Source ${index + 1} (${source.bookSourceName}): homepage unreachable or empty, skipped")
				}
			}
			
			// Skip sources with no explore/list capability when requested
			if (skipNoExplore && (source.ruleExplore == null || source.ruleExplore?.bookList.isNullOrBlank())) {
				return SourceProcessResult.Error("Source ${index + 1} (${source.bookSourceName}): no explore rule, skipped by option")
			}
			
			// Generate unique identifier using URL (following Legado's approach)
			val sourceId = generateSourceId(source.bookSourceUrl, JsonSourceType.LEGADO, source.bookSourceType)
			JsonSourceLogger.logIdGeneration(source.bookSourceName, sourceId)
			
			// Serialize the source back to JSON for storage
			val configJson = json.encodeToString(
				org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource.serializer(),
				source
			).let { rawConfig ->
				JsonSourceImportMetadata.attach(
					rawConfig = rawConfig,
					sourceLocator = sourceLocator,
					sourceTitle = sourceTitle,
					importKind = sourceLocator?.let(::resolveImportKind),
				)
			}
			
			// Create entity
			val timestamp = System.currentTimeMillis()
			val entity = JsonSourceEntity(
				id = sourceId,
				name = source.bookSourceName,
				type = JsonSourceType.LEGADO,
				config = configJson,
				enabled = source.enabled,
				createdAt = timestamp,
				updatedAt = timestamp,
				lastUsedAt = 0,
				isPinned = false,
				iconUrl = deriveFaviconUrl(source.bookSourceUrl),
			)
			
			SourceProcessResult.Success(entity)
		} catch (e: Exception) {
			SourceProcessResult.Error("Source ${index + 1} (${source.bookSourceName}): ${e.message}")
		}
	}

	private fun deriveTvBoxIconUrl(site: JSONObject): String? {
		val directIcon = sequenceOf("logo", "icon", "pic")
			.map { key -> site.optString(key).trim() }
			.firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
		return directIcon ?: deriveFaviconUrl(site.optString("api"))
	}

	private fun deriveFaviconUrl(siteUrl: String?): String? {
		val trimmed = siteUrl?.trim().orEmpty()
		if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
			return null
		}
		return runCatching {
			val uri = java.net.URI(trimmed)
			val scheme = uri.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: return null
			val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
			val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
			"$scheme://$host$port/favicon.ico"
		}.getOrNull()
	}

	private fun resolveImportKind(sourceLocator: String): String {
		return when {
			sourceLocator.startsWith("content://", ignoreCase = true) -> "file"
			sourceLocator.startsWith("file://", ignoreCase = true) -> "file"
			sourceLocator.startsWith("http://", ignoreCase = true) -> "url"
			sourceLocator.startsWith("https://", ignoreCase = true) -> "url"
			else -> "inline"
		}
	}
	
	/**
	 * Result of processing a single source
	 */
	private sealed class SourceProcessResult {
		data class Success(val entity: JsonSourceEntity) : SourceProcessResult()
		data class Error(val message: String) : SourceProcessResult()
	}
	
	/**
	 * Quick reachability check for a source homepage.
	 * Returns false if the request fails or HTML is too short/blank.
	 */
	private suspend fun verifyHomePageAccessible(source: org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource): Boolean {
		val client = legadoHttpClient ?: return true
		val url = source.bookSourceUrl.takeIf { it.isNotBlank() } ?: return true // allow empty URLs
		
		// Parse custom headers if provided
		val headers = parseCustomHeaders(source.header)
		
		val response = client.get(url, headers, source = null)
		val body = response.body?.string().orEmpty()
		response.close()
		
		if (!response.isSuccessful) {
			JsonSourceLogger.logWarning("Homepage check failed for ${source.bookSourceName}: HTTP ${response.code}")
			return false
		}
		if (body.length < 500) {
			JsonSourceLogger.logWarning("Homepage check failed for ${source.bookSourceName}: HTML too short (${body.length} bytes)")
			return false
		}
		return true
	}
	
	private fun parseCustomHeaders(headerStr: String?): Map<String, String> {
		if (headerStr.isNullOrBlank()) return emptyMap()
		return try {
			val jsonStr = headerStr.replace("'", "\"")
			Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(jsonStr)
		} catch (e: Exception) {
			JsonSourceLogger.logWarning("Failed to parse custom headers: ${e.message}")
			emptyMap()
		}
	}
	
	/**
	 * Validates a Legado book source configuration.
	 * 
	 * Checks:
	 * - Required field: bookSourceName must not be blank
	 * - Optional field: bookSourceUrl (can be empty in Legado for certain source types)
	 * - URL format: bookSourceUrl must be a valid URL if not empty
	 * 
	 * Note: Following Legado's validation logic, bookSourceUrl can be empty.
	 * Some sources in Legado use empty bookSourceUrl for specific purposes.
	 * 
	 * @param source The Legado book source to validate
	 * @return ValidationResult indicating whether the source is valid
	 */
	fun validateLegadoSource(source: org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource): ValidationResult {
		val errors = mutableListOf<String>()
		
		// Check required field: bookSourceName
		if (source.bookSourceName.isBlank()) {
			errors.add("bookSourceName is required and cannot be blank")
		}
		
		// bookSourceUrl can be empty in Legado (following Legado's validation logic)
		// Only validate URL format if it's not empty
		if (source.bookSourceUrl.isNotEmpty()) {
			val urlValidation = validateUrl(source.bookSourceUrl)
			if (!urlValidation.isValid) {
				// Log warning but don't fail validation
				// Some Legado sources may have non-standard URLs
				JsonSourceLogger.logWarning("URL validation warning for ${source.bookSourceName}: ${urlValidation.errors.joinToString(", ")}")
			}
		}
		
		return if (errors.isEmpty()) {
			ValidationResult.success()
		} else {
			ValidationResult.failure(errors)
		}
	}
	
	/**
	 * Validates a URL string using SecurityValidator.
	 * 
	 * Checks:
	 * - URL must be parseable
	 * - Protocol must be http or https
	 * - Host must not be blank
	 * - Host must not be a local address
	 * 
	 * @param url The URL string to validate
	 * @return ValidationResult indicating whether the URL is valid
	 */
	private fun validateUrl(url: String): ValidationResult {
		return SecurityValidator.validateUrl(url)
	}

	private suspend fun processTvBoxDocument(
		rawContent: String,
		sourceLocator: String?,
		sourceTitle: String?,
		depth: Int,
		visitedUrls: MutableSet<String>,
		entities: MutableList<JsonSourceEntity>,
		errors: MutableList<String>,
	) {
		val locatorLabel = sourceLocator?.trim().takeUnless { it.isNullOrBlank() } ?: "inline"
		val normalizedContent = preprocessTvBoxJson(rawContent)
		JsonSourceLogger.logInfo("Processing TVBox document: locator=$locatorLabel depth=$depth bytes=${normalizedContent.length}")
		val root = try {
			parseTvBoxRootObject(normalizedContent)
		} catch (e: Exception) {
			JsonSourceLogger.logTvBoxImportFailure(
				category = "json_import",
				action = "parse_root",
				locator = locatorLabel,
				detail = "depth=$depth message=${e.message ?: "invalid TVBox JSON"}",
				error = e,
			)
			errors += "${sourceLocator ?: "inline"}: ${e.message ?: "invalid TVBox JSON"}"
			return
		}

		when {
			root.has("sites") -> {
				val repositoryTitle = sourceTitle ?: extractTvBoxRepositoryRootTitle(root, sourceLocator)
				val importedEntities = buildTvBoxSiteEntities(
					root = root,
					sourceLocator = sourceLocator,
					sourceTitle = repositoryTitle,
				)
				entities += importedEntities
				JsonSourceLogger.logInfo(
					"TVBox repository parsed: locator=$locatorLabel depth=$depth title=${repositoryTitle ?: "-"} declaredSites=${root.optJSONArray("sites")?.length() ?: 0} importedSites=${importedEntities.size}",
				)
			}
			root.has("urls") -> {
				if (depth >= MAX_TVBOX_REPOSITORY_DEPTH) {
					JsonSourceLogger.logTvBoxImportFailure(
						category = "multi_repo",
						action = "depth_limit",
						locator = locatorLabel,
						detail = "depth=$depth limit=$MAX_TVBOX_REPOSITORY_DEPTH",
					)
					errors += "${sourceLocator ?: "inline"}: nested multi-repository depth exceeds limit"
					return
				}
				val childUrls = root.optJSONArray("urls") ?: JSONArray()
				JsonSourceLogger.logInfo(
					"TVBox multi-repository parsed: locator=$locatorLabel depth=$depth title=${sourceTitle ?: extractTvBoxRepositoryRootTitle(root, sourceLocator) ?: "-"} children=${childUrls.length()}",
				)
				for (index in 0 until childUrls.length()) {
					val rawEntry = childUrls.opt(index)
					val entry = parseTvBoxRepositoryEntry(rawEntry)
					val childUrl = entry?.url.orEmpty()
					val childTitle = entry?.title
					JsonSourceLogger.logInfo(
						"TVBox child repository candidate: parent=$locatorLabel depth=$depth index=$index rawType=${rawEntry?.javaClass?.simpleName ?: "null"} title=${childTitle ?: "-"} url=${childUrl.ifBlank { "<blank>" }}",
					)
					if (childUrl.isBlank()) {
						JsonSourceLogger.logTvBoxImportFailure(
							category = "multi_repo",
							action = "child_missing_url",
							locator = locatorLabel,
							detail = "depth=$depth index=$index",
						)
						errors += "${sourceLocator ?: "inline"}: child repository at index $index is missing url"
						continue
					}
					val normalizedChildUrl = normalizeTvBoxFetchUrl(childUrl) ?: childUrl
					if (normalizedChildUrl != childUrl) {
						JsonSourceLogger.logInfo(
							"Normalized TVBox child repository URL: original=$childUrl normalized=$normalizedChildUrl",
						)
					}
					if (!visitedUrls.add(childUrl)) {
						JsonSourceLogger.logInfo(
							"Skipping duplicate TVBox child repository: parent=$locatorLabel depth=$depth index=$index url=$childUrl",
						)
						continue
					}
					val entityCountBefore = entities.size
					val errorCountBefore = errors.size
					val childContent = fetchTvBoxChildRepository(childUrl)
					if (childContent == null) {
						JsonSourceLogger.logTvBoxImportFailure(
							category = "multi_repo",
							action = "child_fetch_empty",
							locator = locatorLabel,
							detail = "depth=$depth index=$index url=$childUrl",
						)
						errors += "$childUrl: fetch failed"
						continue
					}
					processTvBoxDocument(
						rawContent = childContent,
						sourceLocator = childUrl,
						sourceTitle = childTitle,
						depth = depth + 1,
						visitedUrls = visitedUrls,
						entities = entities,
						errors = errors,
					)
					JsonSourceLogger.logInfo(
						"TVBox child repository processed: parent=$locatorLabel depth=$depth index=$index url=$childUrl importedSites=${entities.size - entityCountBefore} newErrors=${errors.size - errorCountBefore}",
					)
				}
			}
			else -> {
				JsonSourceLogger.logTvBoxImportFailure(
					category = "json_import",
					action = "unsupported_root",
					locator = locatorLabel,
					detail = "depth=$depth keys=${root.keySummary()}",
				)
				errors += "${sourceLocator ?: "inline"}: unsupported TVBox JSON, expected 'sites' or 'urls'"
			}
		}
	}

	private val tvboxLenientJson = kotlinx.serialization.json.Json {
		ignoreUnknownKeys = true
		isLenient = true
		allowTrailingComma = true
	}

	private fun preprocessTvBoxJson(rawContent: String): String {
		val withoutBom = rawContent.removePrefix("\uFEFF")
		val cleaned = withoutBom.replace(Regex(""",(?=\s*[\}\]])"""), "")
		val lines = cleaned.lines()
		var started = false
		val builder = StringBuilder()
		for (line in lines) {
			val trimmed = line.trim()
			if (!started) {
				if (trimmed.isBlank() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue
				started = true
			}
			if (trimmed.startsWith("//") || trimmed.startsWith("#")) continue
			if (builder.isNotEmpty()) {
				builder.append('\n')
			}
			builder.append(line)
		}
		return builder.toString().trim()
	}

	private fun detectMacCmsApiUrl(rawContent: String): String? {
		val normalized = normalizeTvBoxFetchUrl(rawContent.trim()) ?: rawContent.trim()
		if (!normalized.startsWith("http://", ignoreCase = true) &&
			!normalized.startsWith("https://", ignoreCase = true)
		) {
			return null
		}
		val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
		val path = "/" + uri.rawPath.orEmpty().trimStart('/').lowercase()
		return if (MAC_CMS_API_PATHS.any { path.startsWith(it) } || path.contains(".php/provide/vod")) {
			URI(uri.scheme, uri.authority, uri.path, null, null).toString()
		} else {
			null
		}
	}

	private fun detectMacCmsPayloadApiUrl(rawContent: String, sourceLocator: String?): String? {
		val locatorApiUrl = detectMacCmsApiUrl(sourceLocator.orEmpty())
		if (locatorApiUrl != null && looksLikeMacCmsPayload(rawContent)) {
			return locatorApiUrl
		}
		return null
	}

	private fun looksLikeMacCmsPayload(rawContent: String): Boolean {
		val normalized = rawContent.removePrefix("\uFEFF").trim()
		if (normalized.startsWith('<')) {
			return normalized.contains("<rss", ignoreCase = true) &&
				normalized.contains("<video", ignoreCase = true)
		}
		val root = runCatching { JSONTokener(normalized).nextValue() as? JSONObject }.getOrNull() ?: return false
		return root.has("list") &&
			(root.has("page") || root.has("pagecount") || root.has("total") || root.has("class"))
	}

	private fun buildMacCmsTvBoxDocument(apiUrl: String, sourceTitle: String?): String {
		val title = sourceTitle?.trim()?.takeIf { it.isNotBlank() } ?: buildTvBoxRepositoryTitle(apiUrl, null)
		val key = "MAC_CMS_${apiUrl.hashCode().toUInt().toString(16).uppercase()}"
		return JSONObject().apply {
			put("name", title)
			put(
				"sites",
				JSONArray().put(
					JSONObject().apply {
						put("key", key)
						put("name", title)
						put("type", 1)
						put("api", apiUrl)
						put("searchable", 1)
						put("quickSearch", 1)
						put("filterable", 1)
					},
				),
			)
		}.toString()
	}

	private fun parseTvBoxRootObject(content: String): JSONObject {
		return try {
			val tokenized = JSONTokener(content).nextValue()
			when (tokenized) {
				is JSONObject -> tokenized
				is JSONArray -> throw IllegalArgumentException("TVBox root must be a JSON object, not array")
				else -> throw IllegalArgumentException("TVBox root must be a JSON object")
			}
		} catch (e: Exception) {
			try {
				val jsonElement = tvboxLenientJson.decodeFromString<kotlinx.serialization.json.JsonObject>(content)
				JSONObject(jsonElement.toString())
			} catch (fallbackEx: Exception) {
				throw e
			}
		}
	}

	private suspend fun buildTvBoxSiteEntities(
		root: JSONObject,
		sourceLocator: String?,
		sourceTitle: String?,
	): List<JsonSourceEntity> {
		val sites = root.optJSONArray("sites") ?: JSONArray()
		if (sites.length() == 0) {
			return emptyList()
		}

		val rootContext = JSONObject().apply {
			copyIfPresent(root, this, "spider")
			copyIfPresent(root, this, "wallpaper")
			copyIfPresent(root, this, "logo")
			copyIfPresent(root, this, "lives")
			copyIfPresent(root, this, "parses")
			copyIfPresent(root, this, "flags")
			copyIfPresent(root, this, "ijk")
			copyIfPresent(root, this, "ads")
			copyIfPresent(root, this, "rules")
			copyIfPresent(root, this, "headers")
			copyIfPresent(root, this, "doh")
		}

		val entities = ArrayList<JsonSourceEntity>(sites.length())
		val timestamp = System.currentTimeMillis()
		for (index in 0 until sites.length()) {
			val site = sites.optJSONObject(index) ?: continue
			val name = site.optString("name").trim().ifBlank { site.optString("key").trim() }
			if (name.isBlank()) {
				JsonSourceLogger.logTvBoxImportFailure(
					category = "json_import",
					action = "site_missing_name",
					locator = sourceLocator?.trim().orEmpty().ifBlank { "inline" },
					detail = "index=$index",
				)
				continue
			}
			val sourceId = generateTvBoxSourceId(sourceLocator, site)
			val configJson = JSONObject().apply {
				put("schemaVersion", 1)
				put("importType", "tvbox_site")
				put("site", JSONObject(site.toString()))
				put("root", JSONObject(rootContext.toString()))
				put("meta", JSONObject().apply {
					put("sourceLocator", sourceLocator ?: JSONObject.NULL)
					put("sourceTitle", sourceTitle ?: JSONObject.NULL)
					put("siteIndex", index)
					put("siteKey", site.optString("key"))
					put("siteApi", site.optString("api"))
				})
			}.toString()

			entities += JsonSourceEntity(
				id = sourceId,
				name = name,
				type = JsonSourceType.TVBOX,
				config = configJson,
				enabled = true,
				createdAt = timestamp,
				updatedAt = timestamp,
				lastUsedAt = 0,
				isPinned = false,
				iconUrl = deriveTvBoxIconUrl(site),
			)
		}
		return entities
	}

	private suspend fun generateTvBoxSourceId(
		sourceLocator: String?,
		site: JSONObject,
	): String {
		val fingerprint = buildString {
			append(sourceLocator?.trim().orEmpty())
			append('|')
			append(site.optString("key").trim())
			append('|')
			append(site.optString("name").trim())
			append('|')
			append(site.optString("api").trim())
			append('|')
			append(site.opt("ext")?.toString()?.trim().orEmpty())
		}
		return generateSourceId(fingerprint, JsonSourceType.TVBOX)
	}

	private suspend fun fetchTvBoxChildRepository(url: String): String? {
		val requestUrl = normalizeTvBoxFetchUrl(url) ?: url
		if (requestUrl != url) {
			JsonSourceLogger.logInfo("Normalized TVBox child request URL from $url to $requestUrl")
		}
		val validation = SecurityValidator.validateUrl(requestUrl)
		if (!validation.isValid) {
			JsonSourceLogger.logTvBoxImportFailure(
				category = "multi_repo",
				action = "child_url_invalid",
				locator = url,
				detail = validation.errors.joinToString(", "),
			)
			return null
		}
		val client = legadoHttpClient ?: run {
			JsonSourceLogger.logTvBoxImportFailure(
				category = "multi_repo",
				action = "http_client_missing",
				locator = url,
				detail = "LegadoHttpClient is unavailable",
			)
			return null
		}
		return try {
			JsonSourceLogger.logInfo("Fetching TVBox child repository: source=$url request=$requestUrl")
			val response = client.get(requestUrl)
			if (!response.isSuccessful) {
				JsonSourceLogger.logTvBoxImportFailure(
					category = "multi_repo",
					action = "child_http_error",
					locator = url,
					detail = "request=$requestUrl status=${response.code}",
				)
				response.close()
				return null
			}
			val body = response.body?.string()
			response.close()
			if (body.isNullOrBlank()) {
				JsonSourceLogger.logTvBoxImportFailure(
					category = "multi_repo",
					action = "child_empty_body",
					locator = url,
					detail = "request=$requestUrl",
				)
				return null
			}
			JsonSourceLogger.logInfo("Fetched TVBox child repository: source=$url request=$requestUrl bytes=${body.length}")
			body
		} catch (e: Exception) {
			JsonSourceLogger.logNetworkError(url, e)
			JsonSourceLogger.logTvBoxImportFailure(
				category = "multi_repo",
				action = "child_network_error",
				locator = url,
				detail = e.message ?: e.javaClass.simpleName,
				error = e,
			)
			null
		}
	}

	private fun copyIfPresent(from: JSONObject, to: JSONObject, key: String) {
		if (from.has(key)) {
			to.put(key, from.get(key))
		}
	}

	private fun parseTvBoxRepositoryEntry(raw: Any?): TvBoxRepositoryEntry? {
		return when (raw) {
			null, JSONObject.NULL -> null
			is JSONObject -> {
				val title = listOf("name", "title", "siteName")
					.firstNotNullOfOrNull { key -> raw.optString(key).trim().ifBlank { null } }
				val url = listOf("url", "api", "ext", "link", "file")
					.firstNotNullOfOrNull { key -> raw.optString(key).trim().ifBlank { null } }
					?: return null
				TvBoxRepositoryEntry(url = url, title = title)
			}
			is String -> parseTvBoxRepositoryEntryString(raw)
			else -> parseTvBoxRepositoryEntryString(raw.toString())
		}
	}

	private fun parseTvBoxRepositoryEntryString(raw: String): TvBoxRepositoryEntry? {
		val trimmed = raw.trim()
		if (trimmed.isBlank()) {
			return null
		}
		val schemes = listOf("https://", "http://", "clan://", "file://", "content://")
		val schemeIndex = schemes.mapNotNull { scheme ->
			trimmed.indexOf(scheme).takeIf { it >= 0 }
		}.minOrNull()
		return if (schemeIndex == null) {
			TvBoxRepositoryEntry(url = trimmed, title = null)
		} else {
			val title = trimmed.substring(0, schemeIndex)
				.trim()
				.trim('|', ',', ';', '$', '，', '；')
				.ifBlank { null }
			val url = trimmed.substring(schemeIndex).trim()
			TvBoxRepositoryEntry(url = url, title = title)
		}
	}

	private fun extractTvBoxSourceLocator(rawConfig: String): String? {
		return runCatching {
			JSONObject(rawConfig)
				.optJSONObject("meta")
				?.optString("sourceLocator")
				?.trim()
				?.ifBlank { null }
		}.getOrNull()
	}

	private fun extractTvBoxSourceTitle(rawConfig: String): String? {
		return runCatching {
			JSONObject(rawConfig)
				.optJSONObject("meta")
				?.optString("sourceTitle")
				?.trim()
				?.ifBlank { null }
		}.getOrNull()
	}

	private fun extractTvBoxRepositoryRootTitle(root: JSONObject, sourceLocator: String?): String? {
		return listOf(
			root.optString("name").trim().ifBlank { null },
			root.optString("title").trim().ifBlank { null },
			root.optString("siteName").trim().ifBlank { null },
		).firstOrNull { !it.isNullOrBlank() } ?: sourceLocator?.let { buildTvBoxRepositoryTitle(it, null) }
	}

	private fun buildTvBoxRepositoryTitle(locator: String, sourceTitle: String?): String {
		sourceTitle?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
		val uri = runCatching { android.net.Uri.parse(locator) }.getOrNull()
		val host = uri?.host?.trim().orEmpty()
		val tail = uri?.lastPathSegment?.trim().orEmpty()
		return when {
			host.isNotBlank() && tail.isNotBlank() -> "$host · $tail"
			host.isNotBlank() -> host
			else -> locator.substringAfterLast('/').ifBlank { locator }
		}
	}

	private fun normalizeTvBoxFetchUrl(rawUrl: String): String? {
		val match = TVBOX_URL_REGEX.find(rawUrl.trim()) ?: return rawUrl
		val scheme = match.groupValues[1]
		val authority = match.groupValues[2]
		val suffix = match.groupValues[3]
		if (authority.isBlank()) {
			return rawUrl
		}
		val userInfo = authority.substringBefore('@', "").ifBlank { null }
		val hostPort = authority.substringAfter('@', authority)
		val host = hostPort.substringBefore(':').ifBlank { hostPort }
		val port = hostPort.substringAfter(':', "").ifBlank { null }
		val asciiHost = runCatching { IDN.toASCII(host) }.getOrDefault(host)
		if (asciiHost == host) {
			return rawUrl
		}
		return buildString {
			append(scheme)
			append("://")
			userInfo?.let {
				append(it)
				append('@')
			}
			append(asciiHost)
			port?.let {
				append(':')
				append(it)
			}
			append(suffix)
		}
	}

	private data class TvBoxRepositoryEntry(
		val url: String,
		val title: String?,
	)

	private fun JSONObject.keySummary(limit: Int = 8): String {
		val keyNames = mutableListOf<String>()
		val iterator = keys()
		while (iterator.hasNext() && keyNames.size < limit) {
			keyNames += iterator.next()
		}
		return if (keyNames.isEmpty()) {
			"<none>"
		} else {
			keyNames.joinToString(", ")
		}
	}

	/**
	 * Generates a unique source identifier from source URL and name.
	 * 
	 * Following Legado's approach, we use the bookSourceUrl as the primary identifier.
	 * The identifier follows the format: JSON_[TYPE_]URL_HASH
	 * where:
	 * - JSON_ is the prefix for all JSON sources
	 * - [TYPE_] is the type prefix (LEGADO_ or TVBOX_) to distinguish source types
	 * - URL_HASH is a hash of the bookSourceUrl to ensure uniqueness
	 * 
	 * This approach ensures:
	 * 1. Each source with a unique URL gets a unique ID
	 * 2. Works with any language (Chinese, Japanese, etc.)
	 * 3. Consistent with Legado's use of bookSourceUrl as primary key
	 * 
	 * @param sourceUrl The source URL (bookSourceUrl in Legado)
	 * @param sourceType The type of JSON source (LEGADO or TVBOX)
	 * @return A unique identifier string
	 */
	suspend fun generateSourceId(sourceUrl: String, sourceType: JsonSourceType, bookSourceType: Int = 0): String {
		// Build the type prefix
		val typePrefix = when (sourceType) {
			JsonSourceType.LEGADO -> if (bookSourceType == 2) LEGADO_MANGA_PREFIX else LEGADO_PREFIX
			JsonSourceType.TVBOX -> TVBOX_PREFIX
			JsonSourceType.JS -> JS_PREFIX
			JsonSourceType.LNREADER -> LNREADER_PREFIX
		}
		
		// Generate a hash of the URL to create a unique, stable identifier
		// Using hashCode() and converting to hex for readability
		val urlHash = sourceUrl.hashCode().toUInt().toString(16).uppercase()
		
		// Build the identifier
		val sourceId = "$typePrefix$urlHash"
		
		JsonSourceLogger.logDebug("Generated ID $sourceId for URL: $sourceUrl")
		
		return sourceId
	}
	
	/**
	 * Legacy method for backward compatibility - now delegates to URL-based generation
	 * @deprecated Use generateSourceId(sourceUrl, sourceType) instead
	 */
	@Deprecated("Use generateSourceId with sourceUrl parameter")
	suspend fun generateSourceIdFromName(sourceName: String, sourceType: JsonSourceType, bookSourceType: Int = 0): String {
		// For legacy calls, generate a UUID-based ID
		val uuid = java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
		val typePrefix = when (sourceType) {
			JsonSourceType.LEGADO -> if (bookSourceType == 2) LEGADO_MANGA_PREFIX else LEGADO_PREFIX
			JsonSourceType.TVBOX -> TVBOX_PREFIX
			JsonSourceType.JS -> JS_PREFIX
			JsonSourceType.LNREADER -> LNREADER_PREFIX
		}
		return "$typePrefix$uuid"
	}
	
	/**
	 * Generates a simple source identifier without type prefix (for backward compatibility).
	 * 
	 * @param sourceName The original source name
	 * @return A unique identifier string with JSON_ prefix
	 */
	suspend fun generateSimpleSourceId(sourceName: String): String {
		val normalizedName = sourceName
			.uppercase()
			.replace(" ", "_")
			.replace(VALID_CHAR_REGEX, "")
			.take(50)
		
		val baseId = "$JSON_PREFIX$normalizedName"
		var candidateId = baseId
		var suffix = 1
		
		val existingIds = jsonSourceDao.observeAll().first().map { it.id }.toSet()
		
		while (existingIds.contains(candidateId)) {
			candidateId = "${baseId}_$suffix"
			suffix++
		}
		
		return candidateId
	}
}
