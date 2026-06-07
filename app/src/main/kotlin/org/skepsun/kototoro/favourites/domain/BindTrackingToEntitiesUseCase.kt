package org.skepsun.kototoro.favourites.domain

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TrackingMetadataSourceStrategy
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.TrackingCharacterDto
import org.skepsun.kototoro.entitygraph.domain.TrackingPersonDto
import org.skepsun.kototoro.entitygraph.domain.TrackingStaffDto
import org.skepsun.kototoro.entitygraph.domain.TrackingWorkDto
import org.skepsun.kototoro.entitygraph.domain.normalizeEntityName
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatcher
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.tracking.mangabaka.data.MangaBakaMetadataRepository
import javax.inject.Inject

private const val TAG = "BindTrackingUseCase"
private const val DATASET_LOOKUP_TIMEOUT_MS = 1500L

data class BindTrackingResult(
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

data class TrackingBindingPreview(
    val previewId: String,
    val groupId: String,
    val title: String,
    val contentTypeName: String,
    val service: ScrobblerService,
    val remoteId: Long,
    val matchedTitle: String,
    val matchedAltTitle: String?,
    val url: String?,
    val confidence: Float,
    val matchedBy: TrackingBindingMatchKind,
    val year: Int?,
    val details: TrackingSiteItemDetails,
    val aliases: List<String> = emptyList(),
)

enum class TrackingBindingMatchKind {
    ANIME_DATASET,
    AGGREGATE_API,
    ONLINE_SEARCH,
    EXISTING_BINDING,
}

data class TrackingBindingPreviewResult(
    val previews: List<TrackingBindingPreview>,
    val skipped: Int,
)

class BindTrackingToEntitiesUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val appSettings: AppSettings,
    private val discoveryService: TrackingSiteDiscoveryService,
    private val trackingSiteCacheRepository: TrackingSiteCacheRepository,
    private val animeOfflineRepository: AnimeOfflineRepository,
    private val mangaBakaMetadataRepository: MangaBakaMetadataRepository,
    private val trackingSiteMatcher: TrackingSiteMatcher,
    private val entityGraphRepository: EntityGraphRepository,
    private val contentDataRepository: ContentDataRepository,
) {

    suspend fun preview(
        groups: List<MergeCandidateGroup>,
        services: List<ScrobblerService>,
        representativeContents: Map<Long, Content> = emptyMap(),
        onProgress: ((MigrationProgress) -> Unit)? = null,
    ): TrackingBindingPreviewResult {
        if (services.isEmpty()) {
            return TrackingBindingPreviewResult(
                previews = emptyList(),
                skipped = groups.size,
            )
        }
        val previews = mutableListOf<TrackingBindingPreview>()
        var skipped = 0
        var matched = 0
        val total = groups.size
        groups.forEach { group ->
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = matched,
                    failed = 0,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                        title = group.title,
                    ),
                    items = emptyList(),
                ),
            )
            val representativeId = group.mangaIds.firstOrNull()
            Log.d(
                TAG,
                "preview group start: group=${group.id}, title=${group.title}, representativeId=$representativeId, " +
                    "cachedRepresentative=${representativeId != null && representativeId in representativeContents}",
            )
            val representative = representativeId?.let { representativeContents[it] ?: database.getMangaDao().find(it)?.toContent() }
            Log.d(
                TAG,
                "preview group representative resolved: group=${group.id}, representativeId=$representativeId, found=${representative != null}",
            )
            if (representative == null) {
                skipped++
                onProgress?.invoke(
                    MigrationProgress(
                        total = total,
                        completed = matched,
                        failed = 0,
                        notFound = skipped,
                        currentItem = MigrationItem(
                            mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                            title = group.title,
                        ),
                        items = emptyList(),
                        isFinished = matched + skipped >= total,
                    ),
                )
                return@forEach
            }
            val resolvedCandidates = resolveTrackingDetails(representative, services)
            if (resolvedCandidates.isEmpty()) {
                skipped++
                onProgress?.invoke(
                    MigrationProgress(
                        total = total,
                        completed = matched,
                        failed = 0,
                        notFound = skipped,
                        currentItem = MigrationItem(
                            mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                            title = group.title,
                        ),
                        items = emptyList(),
                        isFinished = matched + skipped >= total,
                    ),
                )
                return@forEach
            }
            previews += resolvedCandidates.map { resolved ->
                TrackingBindingPreview(
                    previewId = "${group.id}:${resolved.service.id}:${resolved.details.remoteId}",
                    groupId = group.id,
                    title = group.title,
                    contentTypeName = group.contentType.name,
                    service = resolved.service,
                    remoteId = resolved.details.remoteId,
                    matchedTitle = resolved.details.title,
                    matchedAltTitle = resolved.details.altTitle,
                    url = resolved.details.url,
                    confidence = resolved.confidence.coerceIn(0f, 1f),
                    matchedBy = resolved.matchedBy,
                    year = resolved.details.year,
                    details = resolved.details,
                    aliases = resolved.aliases,
                )
            }
            matched++
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = matched,
                    failed = 0,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                        title = group.title,
                    ),
                    items = emptyList(),
                    isFinished = matched + skipped >= total,
                ),
            )
        }
        return TrackingBindingPreviewResult(
            previews = previews,
            skipped = skipped,
        )
    }

    suspend fun bind(
        groups: List<MergeCandidateGroup>,
        previews: List<TrackingBindingPreview>,
        onProgress: ((MigrationProgress) -> Unit)? = null,
    ): BindTrackingResult {
        val previewByGroupId = previews.associateBy { it.groupId }
        var succeeded = 0
        var failed = 0
        var skipped = 0
        val total = groups.size
        Log.d(
            TAG,
            "bind: groups=$total, previews=${previews.size}, previewGroups=${previewByGroupId.size}",
        )
        for (group in groups) {
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = succeeded,
                    failed = failed,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                        title = group.title,
                    ),
                    items = emptyList(),
                ),
            )
            when {
                group.id !in previewByGroupId -> {
                    skipped++
                    Log.d(TAG, "bind skipped: group=${group.title}, reason=no_preview")
                }

                else -> {
                    val preview = checkNotNull(previewByGroupId[group.id])
                    val result = runCatching { bindOne(group, preview) }
                    when {
                        result.getOrDefault(false) -> succeeded++
                        else -> {
                            failed++
                            val error = result.exceptionOrNull()
                            Log.e(
                                TAG,
                                "bind failed: group=${group.title}, service=${preview.service.id}, remoteId=${preview.remoteId}, " +
                                    "matchedTitle=${preview.matchedTitle}, error=${error?.message ?: "bind_returned_false"}",
                                error,
                            )
                        }
                    }
                }
            }
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = succeeded,
                    failed = failed,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = group.items.firstOrNull()?.mangaId ?: -1L,
                        title = group.title,
                    ),
                    items = emptyList(),
                    isFinished = succeeded + failed + skipped >= total,
                ),
            )
            if ((succeeded + failed + skipped) == 1 || (succeeded + failed + skipped) % 10 == 0 || succeeded + failed + skipped >= total) {
                Log.d(
                    TAG,
                    "bind progress: completed=${succeeded + failed + skipped}/$total, succeeded=$succeeded, failed=$failed, skipped=$skipped, current=${group.title}",
                )
            }
        }
        return BindTrackingResult(
            succeeded = succeeded,
            failed = failed,
            skipped = skipped,
        )
    }

    private suspend fun bindOne(
        group: MergeCandidateGroup,
        preview: TrackingBindingPreview,
    ): Boolean {
        val ingestedEntity = entityGraphRepository.ingestWorkFromTracking(
            source = preview.service.id.toString(),
            workDto = preview.details.toTrackingWorkDto(
                additionalAliases = preview.aliases + preview.details.inferAdditionalAliases(preview),
            ),
        )
        val targetEntityId = database.withTransaction {
            val dao = database.getEntityGraphDao()
            val entityIdsToMerge = linkedSetOf<Long>()
            group.mangaIds.forEach { mangaId ->
                val existingBinding = dao.findBinding("local_manga", mangaId.toString())
                    ?: dao.findBinding("0", mangaId.toString())
                if (existingBinding != null && existingBinding.entityId != ingestedEntity.id) {
                    entityIdsToMerge += existingBinding.entityId
                }
                if (existingBinding == null) {
                    dao.upsertBinding(
                        EntityBindingRecord(
                            entityId = ingestedEntity.id,
                            source = "local_manga",
                            externalId = mangaId.toString(),
                            confidence = 1f,
                            isPrimary = false,
                        ),
                    )
                }
                trackingSiteMatcher.confirmMatch(preview.service, mangaId, preview.remoteId)
            }
            val conflictingEntityIds = (entityIdsToMerge + ingestedEntity.id).distinct()
            if (conflictingEntityIds.isNotEmpty()) {
                dao.deleteBindingsByEntityIdsAndSourcesExcept(
                    entityIds = conflictingEntityIds,
                    sources = preview.service.bindingSourceKeys(),
                    externalId = preview.remoteId.toString(),
                )
            }
            if (entityIdsToMerge.isNotEmpty()) {
                Log.d(
                    TAG,
                    "bindOne merge entities: target=${ingestedEntity.id}, sources=${entityIdsToMerge.joinToString()} for group=${group.title}",
                )
                entityGraphRepository.mergeEntities(
                    targetEntityId = ingestedEntity.id,
                    sourceEntityIds = entityIdsToMerge,
                ) ?: return@withTransaction null
            } else {
                ingestedEntity.id
            }
        } ?: return false
        syncEntityMetadataSelection(
            entityId = targetEntityId,
            selection = ContentDataRepository.MetadataSourceSelection.Tracking(
                serviceId = preview.service.id,
                remoteId = preview.remoteId,
            ),
        )
        return true
    }

    private suspend fun syncEntityMetadataSelection(
        entityId: Long,
        selection: ContentDataRepository.MetadataSourceSelection,
    ) {
        val localMangaIds = entityGraphRepository.getBindings(entityId)
            .asSequence()
            .filter { it.source == "local_manga" || it.source == "0" }
            .mapNotNull { it.externalId.toLongOrNull() }
            .distinct()
            .toList()
        contentDataRepository.setEntityMetadataSourceSelection(
            entityId = entityId,
            selection = selection,
            mirrorLocalMangaIds = localMangaIds,
        )
        localMangaIds.forEach { mangaId ->
            contentDataRepository.setIgnoredTrackingSuggestion(
                mangaId = mangaId,
                suggestion = null,
            )
        }
    }

    private suspend fun resolveTrackingDetails(
        content: Content,
        services: List<ScrobblerService>,
    ): List<ResolvedTrackingPreview> = coroutineScope {
        Log.d(
            TAG,
            "resolveTrackingDetails start: title=${content.title}, services=${services.joinToString { it.id.toString() }}",
        )
        val context = buildTrackingCandidateContext(content)
        Log.d(
            TAG,
            "resolveTrackingDetails context ready: title=${content.title}, localCanonical=${context.localCanonical != null}, " +
                "datasetAliases=${context.datasetAliases.size}, apiEnabled=${context.apiCandidatesDeferred != null}",
        )
        services.map { service ->
            async {
                ensureActive()
                runCatchingCancellable {
                    resolveTrackingDetailsForService(content, service, context)
                }.getOrNull()
            }
        }.awaitAll()
            .filterNotNull()
            .distinctBy { "${it.service.id}:${it.details.remoteId}" }
            .sortedWith(
                compareByDescending<ResolvedTrackingPreview> { it.confidence }
                    .thenBy { services.indexOf(it.service).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.details.title },
            )
    }

    private suspend fun resolveTrackingDetailsForService(
        content: Content,
        service: ScrobblerService,
        context: TrackingCandidateContext,
    ): ResolvedTrackingPreview? {
        if (!discoveryService.getCapabilities(service).supportsSearch) {
            Log.d(
                TAG,
                "preview service skipped: title=${content.title}, service=${service.id}, reason=capability_no_search",
            )
            return null
        }
        val strategy = appSettings.trackingMetadataSourceStrategy
        Log.d(
            TAG,
            "preview service start: title=${content.title}, service=${service.id}, strategy=$strategy, " +
                "localCanonical=${context.localCanonical != null}, datasetAliases=${context.datasetAliases.size}, " +
                "apiEnabled=${context.apiCandidatesDeferred != null}",
        )
        val preferredSources = resolvePreferredMetadataSources(strategy)
        preferredSources.forEach { source ->
            when (source) {
                MetadataCandidateSource.LOCAL_DATASET -> {
                    val resolved = resolveFromLocalDataset(service, context)
                    if (resolved != null) {
                        Log.d(
                            TAG,
                            "preview hit: title=${content.title}, service=${service.id}, source=local_dataset, " +
                                "remoteId=${resolved.details.remoteId}, matchedTitle=${resolved.details.title}",
                        )
                        return resolved
                    } else {
                        Log.d(
                            TAG,
                            "preview source miss: title=${content.title}, service=${service.id}, source=local_dataset",
                        )
                    }
                }

                MetadataCandidateSource.AGGREGATE_API -> {
                    val resolved = resolveFromAggregateApi(content, service, context)
                    if (resolved != null) {
                        Log.d(
                            TAG,
                            "preview hit: title=${content.title}, service=${service.id}, source=aggregate_api, " +
                                "remoteId=${resolved.details.remoteId}, matchedTitle=${resolved.details.title}",
                        )
                        return resolved
                    } else {
                        Log.d(
                            TAG,
                            "preview source miss: title=${content.title}, service=${service.id}, source=aggregate_api",
                        )
                    }
                }
            }
        }
        val searchQueries = buildTrackingSearchQueries(content, service, context, strategy)
        Log.d(
            TAG,
            "preview fallback search: title=${content.title}, service=${service.id}, queries=${searchQueries.size}, " +
                "firstQuery=${searchQueries.firstOrNull()}",
        )
        for (query in searchQueries) {
            val searchItem = discoveryService.search(
                TrackingSiteCatalog(
                    service = service,
                    query = query,
                    contentType = content.source.contentType,
                ),
            ).firstOrNull() ?: continue
            val details = trackingSiteCacheRepository.readDetails(service, searchItem.remoteId)
                ?: discoveryService.getDetails(service, searchItem.remoteId, searchItem.url).also {
                    trackingSiteCacheRepository.saveDetails(it)
                }
            val confidence = computeTitleConfidence(
                localTitle = content.title,
                remoteTitle = details.title,
                remoteAltTitle = details.altTitle,
            )
            Log.d(
                TAG,
                "resolveTrackingDetailsForService search: service=${service.id}, query=$query, remoteId=${details.remoteId}, " +
                    "title=${details.title}, confidence=$confidence",
            )
            if (confidence >= EXACT_MATCH_THRESHOLD || query == searchQueries.last()) {
                return ResolvedTrackingPreview(
                    service = service,
                    details = details,
                    matchedBy = TrackingBindingMatchKind.ONLINE_SEARCH,
                    confidence = confidence,
                    aliases = context.allAliasesWithApi(),
                )
            }
        }
        return null
    }

    private suspend fun buildTrackingCandidateContext(
        content: Content,
    ): TrackingCandidateContext {
        Log.d(TAG, "candidate context start: title=${content.title}, step=resolveCanonical")
        val localCanonical = withTimeoutOrNull(DATASET_LOOKUP_TIMEOUT_MS) {
            runCatchingCancellable {
                animeOfflineRepository.resolveCanonicalMatchForLocalVideoContent(content)
            }.getOrNull()
        }.also { result ->
            Log.d(
                TAG,
                "candidate context finish: title=${content.title}, step=resolveCanonical, found=${result != null}",
            )
        }
        Log.d(TAG, "candidate context start: title=${content.title}, step=resolveAliases")
        val datasetAliases = withTimeoutOrNull(DATASET_LOOKUP_TIMEOUT_MS) {
            runCatchingCancellable {
                animeOfflineRepository.resolveCandidateTitlesForLocalVideoContent(content)
            }.getOrDefault(emptyList())
        } ?: emptyList<String>().also {
            Log.d(
                TAG,
                "candidate context timeout: title=${content.title}, step=resolveAliases, timeoutMs=$DATASET_LOOKUP_TIMEOUT_MS",
            )
        }
        Log.d(
            TAG,
            "candidate context finish: title=${content.title}, step=resolveAliases, aliases=${datasetAliases.size}",
        )
        Log.d(TAG, "candidate context start: title=${content.title}, step=readStrategy")
        val strategy = appSettings.trackingMetadataSourceStrategy
        Log.d(TAG, "candidate context finish: title=${content.title}, step=readStrategy, strategy=$strategy")
        Log.d(TAG, "candidate context start: title=${content.title}, step=buildBaseAliases")
        val baseAliases = buildList {
            add(content.title)
            addAll(content.altTitles)
            localCanonical?.title?.let(::add)
            localCanonical?.synonyms?.let(::addAll)
            addAll(datasetAliases)
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeTitle)
        Log.d(
            TAG,
            "candidate context finish: title=${content.title}, step=buildBaseAliases, aliases=${baseAliases.size}",
        )
        Log.d(TAG, "candidate context start: title=${content.title}, step=prepareAggregateDeferred")
        val currentScope = CoroutineScope(currentCoroutineContext())
        val apiCandidatesDeferred = if (strategy !in API_ENABLED_STRATEGIES) {
            Log.d(
                TAG,
                "preview context: title=${content.title}, strategy=$strategy, aggregate_api=disabled_by_strategy",
            )
            null
        } else {
            currentScope.async(start = CoroutineStart.LAZY) {
                Log.d(
                    TAG,
                    "preview context: title=${content.title}, aggregate_api=loading",
                )
                resolveAggregateApiCandidates(content, datasetAliases)
            }
        }
        Log.d(
            TAG,
            "candidate context finish: title=${content.title}, step=prepareAggregateDeferred, enabled=${apiCandidatesDeferred != null}",
        )
        return TrackingCandidateContext(
            datasetAliases = datasetAliases,
            localCanonical = localCanonical,
            baseAliases = baseAliases,
            apiCandidatesDeferred = apiCandidatesDeferred,
        )
    }

    private suspend fun resolveAggregateApiCandidates(
        content: Content,
        datasetAliases: List<String>,
    ): List<AggregateCandidateEnvelope> {
        val searchIndexState = runCatchingCancellable {
            mangaBakaMetadataRepository.readSearchIndexState()
        }.getOrNull()
        val queries = buildList {
            add(content.title)
            addAll(content.altTitles)
            addAll(datasetAliases)
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeTitle)
            .take(4)
        Log.d(
            TAG,
            "aggregate source state: title=${content.title}, indexUsable=${searchIndexState?.isUsable}, " +
                "indexExists=${searchIndexState?.indexExists}, datasetVersion=${searchIndexState?.datasetVersion}, " +
                "indexVersion=${searchIndexState?.indexVersion}",
        )
        val results = linkedMapOf<String, AggregateCandidateEnvelope>()
        for (query in queries) {
            val localCandidates = runCatchingCancellable {
                mangaBakaMetadataRepository.searchSeriesLocal(query, limit = 3)
            }.onFailure { error ->
                Log.w(TAG, "aggregate index search failed: query=$query, error=${error.message}")
            }.getOrDefault(emptyList())
            val candidates = if (localCandidates.isNotEmpty()) {
                Log.d(TAG, "aggregate index resolved: query=$query, candidates=${localCandidates.size}")
                localCandidates.map { AggregateCandidateEnvelope(it, AggregateCandidateOrigin.MANGABAKA_INDEX) }
            } else {
                runCatchingCancellable {
                    mangaBakaMetadataRepository.searchSeries(query, limit = 3)
                }.onFailure { error ->
                    Log.w(TAG, "aggregate api search failed: query=$query, error=${error.message}")
                }.getOrDefault(emptyList())
                    .map { AggregateCandidateEnvelope(it, AggregateCandidateOrigin.MANGABAKA_API) }
            }
            candidates.forEach { envelope ->
                val key = envelope.candidate.aliases.firstOrNull { it.isNotBlank() }?.let(::normalizeTitle)
                    ?: normalizeTitle(envelope.candidate.title)
                if (key.isBlank()) return@forEach
                results.putIfAbsent(key, envelope)
            }
            Log.d(
                TAG,
                "aggregate candidate batch: title=${content.title}, query=$query, source=${candidates.firstOrNull()?.origin?.logLabel ?: "none"}, candidates=${candidates.size}",
            )
            if (results.size >= 5) {
                break
            }
        }
        Log.d(
            TAG,
            "aggregate api resolved: title=${content.title}, queries=${queries.size}, candidates=${results.size}",
        )
        return results.values.toList()
    }

    private suspend fun resolveFromLocalDataset(
        service: ScrobblerService,
        context: TrackingCandidateContext,
    ): ResolvedTrackingPreview? {
        val mapping = context.localCanonical?.mappings?.firstOrNull { it.service == service } ?: return null
        val details = readOrFetchDetails(service, mapping.remoteId, mapping.url)
        return ResolvedTrackingPreview(
            service = service,
            details = details,
            matchedBy = TrackingBindingMatchKind.ANIME_DATASET,
            confidence = 0.995f,
            aliases = context.baseAliases,
        )
    }

    private suspend fun resolveFromAggregateApi(
        content: Content,
        service: ScrobblerService,
        context: TrackingCandidateContext,
    ): ResolvedTrackingPreview? {
        val apiCandidates = context.resolveApiCandidates()
        if (apiCandidates.isEmpty()) {
            Log.d(
                TAG,
                "aggregate api empty: title=${content.title}, service=${service.id}",
            )
            return null
        }
        val bestCandidate = apiCandidates
            .mapNotNull { envelope ->
                val mapping = envelope.candidate.mappings.firstOrNull { it.service == service } ?: return@mapNotNull null
                val confidence = envelope.candidate.aliases.maxOfOrNull { alias ->
                    similarity(normalizeTitle(content.title), normalizeTitle(alias))
                } ?: 0f
                AggregateResolvedCandidate(envelope.candidate, mapping, confidence, envelope.origin)
            }
            .maxByOrNull { it.confidence }
            ?: return null
        val details = readOrFetchDetails(service, bestCandidate.mapping.remoteId, bestCandidate.mapping.url)
        Log.d(
            TAG,
            "resolveTrackingDetailsForService aggregate: service=${service.id}, source=${bestCandidate.origin.logLabel}, " +
                "remoteId=${bestCandidate.mapping.remoteId}, title=${details.title}, confidence=${bestCandidate.confidence}",
        )
        return ResolvedTrackingPreview(
            service = service,
            details = details,
            matchedBy = TrackingBindingMatchKind.AGGREGATE_API,
            confidence = bestCandidate.confidence.coerceAtLeast(0.93f).coerceIn(0f, 1f),
            aliases = context.allAliasesWith(apiCandidates.map(AggregateCandidateEnvelope::candidate)),
        )
    }

    private suspend fun readOrFetchDetails(
        service: ScrobblerService,
        remoteId: Long,
        url: String?,
    ): TrackingSiteItemDetails {
        return trackingSiteCacheRepository.readDetails(service, remoteId)
            ?: discoveryService.getDetails(service, remoteId, url).also {
                trackingSiteCacheRepository.saveDetails(it)
            }
    }

    private fun TrackingSiteItemDetails.toTrackingWorkDto(
        additionalAliases: List<String> = emptyList(),
    ): TrackingWorkDto {
        return TrackingWorkDto(
            externalId = remoteId.toString(),
            primaryName = title,
            contentType = contentType,
            aliases = buildList {
                altTitle?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(additionalAliases)
            }.distinct(),
            characters = characters.map { character ->
                TrackingCharacterDto(
                    externalId = character.id.toString(),
                    primaryName = character.name,
                    voiceActors = character.voiceActors.map { actor ->
                        TrackingPersonDto(
                            externalId = actor.id?.toString(),
                            primaryName = actor.name,
                        )
                    },
                )
            },
            staff = buildList {
                authors.forEach { author ->
                    add(TrackingStaffDto(primaryName = author))
                }
                staff.forEach { person ->
                    add(
                        TrackingStaffDto(
                            externalId = person.id?.toString(),
                            primaryName = person.name,
                            role = person.role,
                        ),
                    )
                }
            },
        )
    }

    private fun TrackingSiteItemDetails.inferAdditionalAliases(
        preview: TrackingBindingPreview,
    ): List<String> {
        return buildList {
            add(preview.title)
            add(preview.matchedTitle)
            preview.matchedAltTitle?.let(::add)
        }.map(String::trim)
            .filter { it.isNotBlank() && it != title && it != altTitle }
            .distinct()
    }

    private data class ResolvedTrackingPreview(
        val service: ScrobblerService,
        val details: TrackingSiteItemDetails,
        val matchedBy: TrackingBindingMatchKind,
        val confidence: Float,
        val aliases: List<String> = emptyList(),
    )

    private data class TrackingCandidateContext(
        val datasetAliases: List<String>,
        val localCanonical: AnimeOfflineRepository.CanonicalMatch?,
        val baseAliases: List<String>,
        val apiCandidatesDeferred: Deferred<List<AggregateCandidateEnvelope>>?,
    ) {
        suspend fun resolveApiCandidates(): List<AggregateCandidateEnvelope> {
            Log.d(
                TAG,
                "aggregate api await: enabled=${apiCandidatesDeferred != null}",
            )
            return apiCandidatesDeferred?.await().orEmpty()
        }

        suspend fun allAliasesWithApi(): List<String> {
            return allAliasesWith(resolveApiCandidates().map(AggregateCandidateEnvelope::candidate))
        }

        fun allAliasesWith(
            apiCandidates: List<MangaBakaMetadataRepository.SeriesCandidate>,
        ): List<String> {
            return (baseAliases + apiCandidates.flatMap(MangaBakaMetadataRepository.SeriesCandidate::aliases))
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { alias ->
                    alias.lowercase()
                        .replace(Regex("\\s+"), "")
                        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9d]"), "")
                }
        }
    }

    private enum class MetadataCandidateSource {
        LOCAL_DATASET,
        AGGREGATE_API,
    }

    private data class AggregateCandidateEnvelope(
        val candidate: MangaBakaMetadataRepository.SeriesCandidate,
        val origin: AggregateCandidateOrigin,
    )

    private data class AggregateResolvedCandidate(
        val candidate: MangaBakaMetadataRepository.SeriesCandidate,
        val mapping: AnimeOfflineRepository.Mapping,
        val confidence: Float,
        val origin: AggregateCandidateOrigin,
    )

    private enum class AggregateCandidateOrigin(val logLabel: String) {
        MANGABAKA_INDEX("mangabaka_index"),
        MANGABAKA_API("mangabaka_api"),
    }

    private fun computeTitleConfidence(
        localTitle: String,
        remoteTitle: String,
        remoteAltTitle: String?,
    ): Float {
        val normalizedLocal = normalizeTitle(localTitle)
        val candidates = listOf(remoteTitle, remoteAltTitle).filterNotNull().map(::normalizeTitle)
        return candidates.maxOfOrNull { candidate -> similarity(normalizedLocal, candidate) } ?: 0f
    }

    private fun normalizeTitle(value: String): String = normalizeEntityName(value)

    private suspend fun buildTrackingSearchQueries(
        content: Content,
        service: ScrobblerService,
        context: TrackingCandidateContext,
        strategy: TrackingMetadataSourceStrategy,
    ): List<String> {
        val baseQueries = buildList {
            add(content.title.trim())
            addAll(content.altTitles.map(String::trim))
        }
        val datasetQueries = context.datasetAliases
        val apiQueries = context.resolveApiCandidates()
            .map(AggregateCandidateEnvelope::candidate)
            .flatMap(MangaBakaMetadataRepository.SeriesCandidate::aliases)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeTitle)
        val orderedByStrategy = when (strategy) {
            TrackingMetadataSourceStrategy.LOCAL_THEN_API -> datasetQueries + baseQueries
            TrackingMetadataSourceStrategy.API_THEN_LOCAL -> apiQueries + datasetQueries + baseQueries
            TrackingMetadataSourceStrategy.LOCAL_ONLY -> datasetQueries + baseQueries
            TrackingMetadataSourceStrategy.API_ONLY -> apiQueries + baseQueries
        }
        val orderedDatasetQueries = if (service == ScrobblerService.BANGUMI) {
            val bridgeQueries = when (strategy) {
                TrackingMetadataSourceStrategy.LOCAL_THEN_API -> apiQueries + datasetQueries
                TrackingMetadataSourceStrategy.API_THEN_LOCAL -> apiQueries + datasetQueries
                TrackingMetadataSourceStrategy.LOCAL_ONLY -> datasetQueries
                TrackingMetadataSourceStrategy.API_ONLY -> apiQueries
            }
            val preferredCjkQueries = bridgeQueries
                .filter(::containsCjk)
                .sortedByDescending { it.length }
            (preferredCjkQueries + orderedByStrategy).distinctBy(::normalizeTitle)
        } else {
            orderedByStrategy
        }
        return orderedDatasetQueries
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeTitle)
    }

    private fun resolvePreferredMetadataSources(
        strategy: TrackingMetadataSourceStrategy,
    ): List<MetadataCandidateSource> {
        return when (strategy) {
            TrackingMetadataSourceStrategy.LOCAL_THEN_API -> listOf(
                MetadataCandidateSource.LOCAL_DATASET,
                MetadataCandidateSource.AGGREGATE_API,
            )

            TrackingMetadataSourceStrategy.API_THEN_LOCAL -> listOf(
                MetadataCandidateSource.AGGREGATE_API,
                MetadataCandidateSource.LOCAL_DATASET,
            )

            TrackingMetadataSourceStrategy.LOCAL_ONLY -> listOf(MetadataCandidateSource.LOCAL_DATASET)
            TrackingMetadataSourceStrategy.API_ONLY -> listOf(MetadataCandidateSource.AGGREGATE_API)
        }
    }

    private fun containsCjk(value: String): Boolean {
        return value.any { char -> char in '\u4E00'..'\u9FFF' }
    }

    private fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val maxLength = maxOf(left.length, right.length).coerceAtLeast(1)
        val distance = levenshtein(left, right)
        return (1f - distance.toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[right.length]
    }

    private companion object {
        const val EXACT_MATCH_THRESHOLD = 0.995f
        val API_ENABLED_STRATEGIES = setOf(
            TrackingMetadataSourceStrategy.LOCAL_THEN_API,
            TrackingMetadataSourceStrategy.API_THEN_LOCAL,
            TrackingMetadataSourceStrategy.API_ONLY,
        )
    }

    private fun ScrobblerService.bindingSourceKeys(): List<String> {
        return buildList {
            add(id.toString())
            add(name.lowercase())
        }.distinct()
    }
}
