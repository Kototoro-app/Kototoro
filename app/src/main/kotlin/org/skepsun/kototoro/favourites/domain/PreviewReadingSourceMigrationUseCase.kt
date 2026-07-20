package org.skepsun.kototoro.favourites.domain

import android.util.Log
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.model.ContentSource as SourceRef
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.isLocalReadingSource
import org.skepsun.kototoro.entitygraph.domain.stripEntityDisambiguationTitleSuffix
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.favourites.domain.MigrationItem
import org.skepsun.kototoro.favourites.domain.MigrationProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchV2Helper
import org.skepsun.kototoro.work.domain.isWorkContentTypeCompatibleWith
import javax.inject.Inject

private const val TAG = "ReadingPreview"
private val ALT_TITLE_SPLIT_REGEX = Regex("[,;\n\r]+")

data class ReadingSourcePreview(
    val mangaId: Long,
    val title: String,
    val targetSourceName: String,
    val targetSourceDisplayName: String = targetSourceName,
    val targetContentId: Long,
    val matchedTitle: String,
    val action: ReadingSourcePreviewAction,
)

enum class ReadingSourcePreviewAction {
    ACTIVATE_EXISTING,
    ATTACH_NEW,
}

data class ReadingSourcePreviewResult(
    val previews: List<ReadingSourcePreview>,
    val skipped: Int,
)

class PreviewReadingSourceMigrationUseCase @Inject constructor(
    private val searchHelperFactory: SearchV2Helper.Factory,
    private val contentDataRepository: ContentDataRepository,
    private val entityGraphRepository: EntityGraphRepository,
) {

    suspend fun preview(
        favourites: List<FavouriteContent>,
        targetSources: List<ContentSource>,
        onProgress: ((MigrationProgress) -> Unit)? = null,
    ): ReadingSourcePreviewResult {
        if (targetSources.isEmpty()) {
            return ReadingSourcePreviewResult(
                previews = emptyList(),
                skipped = favourites.size,
            )
        }
        val searchHelpers = targetSources.associateWith { searchHelperFactory.create(it) }
        val previews = mutableListOf<ReadingSourcePreview>()
        var skipped = 0
        var completed = 0
        val total = favourites.size
        favourites.forEach { favourite ->
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = completed,
                    failed = 0,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = favourite.manga.id,
                        title = favourite.manga.title,
                    ),
                    items = emptyList(),
                ),
            )
            val match = findBestMatch(favourite, targetSources, searchHelpers)
            if (match == null) {
                skipped++
                onProgress?.invoke(
                    MigrationProgress(
                        total = total,
                        completed = completed,
                        failed = 0,
                        notFound = skipped,
                        currentItem = MigrationItem(
                            mangaId = favourite.manga.id,
                            title = favourite.manga.title,
                        ),
                        items = emptyList(),
                        isFinished = completed + skipped >= total,
                    ),
                )
                return@forEach
            }
            val storedContent = contentDataRepository.storeContentAndReturn(match.content, replaceExisting = true)
            previews += ReadingSourcePreview(
                mangaId = favourite.manga.id,
                title = favourite.manga.title,
                targetSourceName = match.source.name,
                targetContentId = storedContent.id,
                matchedTitle = storedContent.title,
                action = match.action,
            )
            completed++
            onProgress?.invoke(
                MigrationProgress(
                    total = total,
                    completed = completed,
                    failed = 0,
                    notFound = skipped,
                    currentItem = MigrationItem(
                        mangaId = favourite.manga.id,
                        title = favourite.manga.title,
                    ),
                    items = emptyList(),
                    isFinished = completed + skipped >= total,
                ),
            )
        }
        return ReadingSourcePreviewResult(
            previews = previews,
            skipped = skipped,
        )
    }

    private suspend fun findBestMatch(
        favourite: FavouriteContent,
        targetSources: List<ContentSource>,
        searchHelpers: Map<ContentSource, SearchV2Helper>,
    ): SourceMatch? {
        val sourceType = favourite.manga.source.let { SourceRef(it).contentType }
        val entityId = entityGraphRepository.findEntityByBinding("local_manga", favourite.manga.id.toString())?.id
            ?: entityGraphRepository.findEntityByBinding("0", favourite.manga.id.toString())?.id
        val searchQueries = buildSearchQueries(favourite, entityId, targetSources)
        Log.d(
            TAG,
            "findBestMatch:start mangaId=${favourite.manga.id} sourceType=${sourceType.name} " +
                "entityId=$entityId queries=${searchQueries.joinToString()}",
        )
        for (targetSource in targetSources) {
            if (!targetSource.contentType.isWorkContentTypeCompatibleWith(sourceType)) {
                Log.d(
                    TAG,
                    "findBestMatch:skipType mangaId=${favourite.manga.id} target=${targetSource.name} " +
                        "targetType=${targetSource.contentType.name} expectedType=${sourceType.name}",
                )
                continue
            }
            val existingProjection = entityId?.let { findExistingProjection(it, targetSource.name) }
            if (existingProjection != null) {
                Log.d(
                    TAG,
                    "findBestMatch:reuse mangaId=${favourite.manga.id} target=${targetSource.name} " +
                        "contentId=${existingProjection.id} title=${existingProjection.title}",
                )
                return SourceMatch(
                    source = targetSource,
                    content = existingProjection,
                    action = ReadingSourcePreviewAction.ACTIVATE_EXISTING,
                )
            }
            val helper = searchHelpers[targetSource] ?: continue
            for (query in searchQueries) {
                val titleResults = runCatchingCancellable {
                    helper(query, SearchKind.TITLE, null)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "findBestMatch:titleSearchFailed mangaId=${favourite.manga.id} target=${targetSource.name} query=$query",
                        error,
                    )
                }.getOrNull()
                val titleMatch = titleResults?.manga?.firstOrNull()
                Log.d(
                    TAG,
                    "findBestMatch:titleSearch mangaId=${favourite.manga.id} target=${targetSource.name} query=$query " +
                        "results=${titleResults?.manga?.size ?: 0} first=${titleMatch?.title}",
                )
                if (titleMatch != null) {
                    return SourceMatch(targetSource, titleMatch, ReadingSourcePreviewAction.ATTACH_NEW)
                }

                val simpleResults = runCatchingCancellable {
                    helper(query, SearchKind.SIMPLE, null)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "findBestMatch:simpleSearchFailed mangaId=${favourite.manga.id} target=${targetSource.name} query=$query",
                        error,
                    )
                }.getOrNull()
                val simpleMatch = simpleResults?.manga?.firstOrNull()
                Log.d(
                    TAG,
                    "findBestMatch:simpleSearch mangaId=${favourite.manga.id} target=${targetSource.name} query=$query " +
                        "results=${simpleResults?.manga?.size ?: 0} first=${simpleMatch?.title}",
                )
                if (simpleMatch != null) {
                    return SourceMatch(targetSource, simpleMatch, ReadingSourcePreviewAction.ATTACH_NEW)
                }
            }
            Log.d(
                TAG,
                "findBestMatch:noMatchOnTarget mangaId=${favourite.manga.id} target=${targetSource.name}",
            )
        }
        Log.d(TAG, "findBestMatch:notFound mangaId=${favourite.manga.id}")
        return null
    }

    private suspend fun buildSearchQueries(
        favourite: FavouriteContent,
        entityId: Long?,
        targetSources: List<ContentSource>,
    ): List<String> {
        val entity = if (entityId != null) {
            entityGraphRepository.getEntity(entityId)
        } else {
            null
        }
        val sourceNames = listOf(favourite.manga.source)
        return buildList<String> {
            add(cleanQuery(favourite.manga.title, sourceNames))
            favourite.manga.altTitles
                ?.split(ALT_TITLE_SPLIT_REGEX)
                .orEmpty()
                .forEach { altTitle ->
                    val normalizedAltTitle = cleanQuery(altTitle, sourceNames)
                    if (normalizedAltTitle.isNotEmpty()) {
                        add(normalizedAltTitle)
                    }
                }
            entity?.let { resolvedEntity ->
                val entitySourceNames = targetSources.mapTo(LinkedHashSet(targetSources.size + 1)) { it.name }
                    .also { it += sourceNames }
                add(cleanQuery(resolvedEntity.primaryName, entitySourceNames))
                resolvedEntity.aliases.forEach { alias ->
                    val normalizedAlias = cleanQuery(alias.toString(), entitySourceNames)
                    if (normalizedAlias.isNotEmpty()) {
                        add(normalizedAlias)
                    }
                }
            }
        }.map { query -> query.trim() }
            .filter { query -> query.isNotEmpty() }
            .distinct()
    }

    private fun cleanQuery(
        value: String,
        sourceNames: Iterable<String>,
    ): String {
        return stripEntityDisambiguationTitleSuffix(value, sourceNames).trim()
    }

    private suspend fun findExistingProjection(
        entityId: Long,
        sourceName: String,
    ): Content? {
        val bindings = entityGraphRepository.getBindings(entityId)
        for (binding in bindings) {
            if (!binding.isLocalReadingSource()) {
                continue
            }
            val mangaId = binding.externalId.toLongOrNull() ?: continue
            val content = contentDataRepository.findContentById(mangaId, withChapters = false) ?: continue
            if (content.source.name == sourceName) {
                return content
            }
        }
        return null
    }

    private data class SourceMatch(
        val source: ContentSource,
        val content: Content,
        val action: ReadingSourcePreviewAction,
    )
}
