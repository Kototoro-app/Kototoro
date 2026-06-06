package org.skepsun.kototoro.favourites.domain

import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.model.ContentSource as SourceRef
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchV2Helper
import javax.inject.Inject

data class ReadingSourcePreview(
    val mangaId: Long,
    val title: String,
    val targetSourceName: String,
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
        favourites.forEach { favourite ->
            val match = findBestMatch(favourite, targetSources, searchHelpers)
            if (match == null) {
                skipped++
                return@forEach
            }
            contentDataRepository.storeContent(match.content, replaceExisting = true)
            previews += ReadingSourcePreview(
                mangaId = favourite.manga.id,
                title = favourite.manga.title,
                targetSourceName = match.source.name,
                targetContentId = match.content.id,
                matchedTitle = match.content.title,
                action = match.action,
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
        val sourceType = favourite.manga.source.let { SourceRef(it).contentType.name }
        val entityId = entityGraphRepository.findEntityByBinding("local_manga", favourite.manga.id.toString())?.id
            ?: entityGraphRepository.findEntityByBinding("0", favourite.manga.id.toString())?.id
        for (targetSource in targetSources) {
            if (targetSource.contentType.name != sourceType) {
                continue
            }
            val existingProjection = entityId?.let { findExistingProjection(it, targetSource.name) }
            if (existingProjection != null) {
                return SourceMatch(
                    source = targetSource,
                    content = existingProjection,
                    action = ReadingSourcePreviewAction.ACTIVATE_EXISTING,
                )
            }
            val helper = searchHelpers[targetSource] ?: continue
            val searchResults = runCatchingCancellable {
                helper(favourite.manga.title, SearchKind.TITLE, null)
            }.getOrNull()
            val match = searchResults?.manga?.firstOrNull() ?: continue
            return SourceMatch(targetSource, match, ReadingSourcePreviewAction.ATTACH_NEW)
        }
        return null
    }

    private suspend fun findExistingProjection(
        entityId: Long,
        sourceName: String,
    ): Content? {
        val bindings = entityGraphRepository.getBindings(entityId)
        for (binding in bindings) {
            if (binding.source != "local_manga" && binding.source != "0") {
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
