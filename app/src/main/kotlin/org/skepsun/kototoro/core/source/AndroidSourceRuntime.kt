package org.skepsun.kototoro.core.source

import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.EmptyContentRepository
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Exposes the existing in-process source loaders to the portable contract without replacing Android UI calls. */
@Singleton
class AndroidSourceRuntime @Inject constructor(
    private val sources: ContentSourcesRepository,
    private val factory: ContentRepository.Factory,
) : SourceRuntime {
    override suspend fun getSources(): List<SourceRef> = sources.getEnabledSources().map { it.toSourceRef() }

    override suspend fun describe(sourceName: String): SourceDescriptor = repository(sourceName).let { repository ->
        SourceDescriptor(
            source = repository.source.toSourceRef(),
            sortOrders = repository.sortOrders.mapTo(linkedSetOf()) { it.name },
            defaultSortOrder = repository.defaultSortOrder.name,
            filterCapabilities = repository.filterCapabilities.toSourceCapabilities(),
            pagingMode = SourcePagingMode.valueOf(repository.listPagingMode.name),
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions =
        repository(sourceName).getFilterOptions().toSourceFilterOptions()

    override suspend fun getList(
        sourceName: String,
        offset: Int,
        order: String?,
        filter: SourceFilter?,
    ): List<SourceContent> {
        val repository = repository(sourceName)
        return repository.getList(
            offset,
            order?.let { parserEnum<SortOrder>(it) },
            filter?.toParser(resolver(repository)),
        ).map { it.toSourceContent() }
    }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
        val repository = repository(content.source.name)
        return repository.getDetails(
            content.toParser(resolver(repository)),
            ContentRepository.DetailsFetchMode.valueOf(fetchMode.name),
        ).toSourceContent()
    }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> {
        val repository = repository(chapter.source.name)
        return repository.getPages(chapter.toParser(resolver(repository)), nextChapterUrl).map { it.toSourcePage() }
    }

    override suspend fun getPageUrl(page: SourcePage): String {
        val repository = repository(page.source.name)
        return repository.getPageUrl(page.toParser(resolver(repository)))
    }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> {
        val repository = repository(content.source.name)
        return repository.getRelated(content.toParser(resolver(repository))).map { it.toSourceContent() }
    }

    private fun repository(sourceName: String): ContentRepository {
        if (sourceName.isEmpty()) throw SourceInvalidArgumentException()
        val result = factory.createWithDiagnostics(ContentSource(sourceName))
        if (result.failureReason != null || result.repository is EmptyContentRepository) {
            throw SourceUnavailableException(sourceName)
        }
        return result.repository
    }

    private fun resolver(repository: ContentRepository): (String) -> ContentSource = { name ->
        if (name == repository.source.name) repository.source else repository(name).source
    }
}
