package org.skepsun.kototoro.core.source

/**
 * One [SourceRuntime] over several ecosystem runtimes. Each source name is served by the first route that owns it,
 * so ecosystems keep their own identity rules (`MIHON_<id>`, parser enum names, ...) without knowing about each other.
 */
class RoutedSourceRuntime(private val routes: List<Route>) : SourceRuntime {
    class Route(val owns: (String) -> Boolean, val runtime: SourceRuntime)

    private fun route(sourceName: String): SourceRuntime =
        routes.firstOrNull { it.owns(sourceName) }?.runtime ?: throw SourceUnavailableException(sourceName)

    override suspend fun getSources(): List<SourceRef> = routes.flatMap { it.runtime.getSources() }.distinctBy { it.name }

    override suspend fun describe(sourceName: String) = route(sourceName).describe(sourceName)

    override suspend fun getFilterOptions(sourceName: String) = route(sourceName).getFilterOptions(sourceName)

    override suspend fun getDynamicFilters(sourceName: String) = route(sourceName).getDynamicFilters(sourceName)

    override suspend fun getPreferences(sourceName: String) = route(sourceName).getPreferences(sourceName)

    override suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ) = route(sourceName).updatePreference(sourceName, revision, nodeId, value)

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?) =
        route(sourceName).getList(sourceName, offset, order, filter)

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) =
        route(content.source.name).getDetails(content, fetchMode)

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) =
        route(chapter.source.name).getPages(chapter, nextChapterUrl)

    override suspend fun getPageUrl(page: SourcePage) = route(page.source.name).getPageUrl(page)

    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?) =
        route(chapter.source.name).getChapterContent(chapter, nextChapterUrl)

    override suspend fun fetchImage(page: SourcePage) = route(page.source.name).fetchImage(page)

    override suspend fun fetchCover(content: SourceContent, large: Boolean) =
        route(content.source.name).fetchCover(content, large)

    override suspend fun getRelated(content: SourceContent) = route(content.source.name).getRelated(content)
}
