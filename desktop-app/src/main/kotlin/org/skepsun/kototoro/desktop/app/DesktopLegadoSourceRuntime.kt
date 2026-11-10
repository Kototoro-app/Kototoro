package org.skepsun.kototoro.desktop.app

import org.jsoup.nodes.Entities
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.core.parser.legado.LegadoExploreKinds
import org.skepsun.kototoro.core.parser.legado.LegadoUrlRules
import org.skepsun.kototoro.core.parser.legado.LegadoRequestPlanner
import org.skepsun.kototoro.core.parser.legado.book.BookChapterList
import org.skepsun.kototoro.core.parser.legado.book.BookContent
import org.skepsun.kototoro.core.parser.legado.book.BookInfo
import org.skepsun.kototoro.core.parser.legado.book.BookList
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoVariableStore
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoListRuntime
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext
import org.skepsun.kototoro.parserhost.toParser
import org.skepsun.kototoro.parserhost.toSourceChapter
import org.skepsun.kototoro.parserhost.toSourceContent
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/** Desktop adapter for the shared Legado novel parsers; the host supplies script, storage and HTTP services. */
internal class DesktopLegadoSourceRuntime(
    private val sources: suspend () -> List<DesktopLegadoSource>,
    private val createEngine: (DesktopLegadoSource) -> JavaScriptEngine,
    private val createExecutor: (DesktopLegadoSource) -> LegadoHttpExecutor,
    private val variableStore: LegadoVariableStore? = null,
) : SourceRuntime {

    override suspend fun getSources(): List<SourceRef> = sources().filter { it.config.bookSourceType == 0 }.map { it.ref }

    override suspend fun describe(sourceName: String): SourceDescriptor {
        val source = source(sourceName)
        return SourceDescriptor(
            source = source.ref,
            sortOrders = setOf("RELEVANCE"),
            defaultSortOrder = "RELEVANCE",
            filterCapabilities = SourceFilterCapabilities(
                isMultipleTagsSupported = false,
                isTagsExclusionSupported = false,
                isSearchSupported = !source.config.searchUrl.isNullOrBlank(),
                isSearchWithFiltersSupported = false,
                isYearSupported = false,
                isYearRangeSupported = false,
                isOriginalLocaleSupported = false,
                isAuthorSearchSupported = false,
            ),
            pagingMode = SourcePagingMode.PAGE_INDEX,
            isChapterContentSupported = true,
            homepage = source.config.bookSourceUrl,
        )
    }

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions {
        val source = source(sourceName)
        // Android's `getFilterOptions`: every discovery category becomes one tag of a single exclusive group.
        val tags = withRuntime(source) { runtime, _ -> exploreKinds(source, runtime.createRuntimeContext(page = 1)) }
            .mapNotNull { kind -> kind.url?.takeIf(String::isNotBlank)?.let { SourceTag(kind.title, CATEGORY_PREFIX + it, source.ref) } }
            .toSet()
        val groups = if (tags.isEmpty()) emptyList() else listOf(SourceTagGroup("分类", tags))
        return SourceFilterOptions(tags, groups, emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
    }

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
        if (offset < 0) throw SourceInvalidArgumentException()
        val source = source(sourceName)
        val query = filter?.query?.trim().orEmpty()
        val category = filter?.tags?.firstOrNull { it.key.startsWith(CATEGORY_PREFIX) }?.key?.removePrefix(CATEGORY_PREFIX)
        return withRuntime(source) { runtime, executor ->
            val key = query.takeIf(String::isNotEmpty)
            val context = runtime.createRuntimeContext(key = key, page = offset + 1)
            // Android's order: a search uses `searchUrl`, browsing the chosen category, else the first one that has a URL.
            val ruleUrl = when {
                query.isNotEmpty() -> source.config.searchUrl
                category != null -> category
                else -> exploreKinds(source, context).firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }
            }
            if (ruleUrl.isNullOrBlank()) return@withRuntime emptyList()
            val webView = if (query.isNotEmpty()) source.config.ruleSearch?.webView else source.config.ruleExplore?.webView
            val plan = requestPlan(source, context, ruleUrl, key, offset + 1, webView == true)
            val response = execute(executor, context, plan)
            BookList.parseWithRuntimeContext(
                response.body, response.url, source.parserSource, source.config, query.isNotEmpty(), context,
            ).map { it.toSourceContent() }
        }
    }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
        val source = source(content.source.name)
        return withRuntime(source) { runtime, executor ->
            val context = runtime.createRuntimeContext()
            val plan = requestPlan(source, context, content.url, webView = source.config.ruleBookInfo?.webView == true)
            val detailsResponse = execute(executor, context, plan)
            val details = BookInfo.parseWithRuntimeContext(
                content.toParser { source.parserSource }, detailsResponse.body, detailsResponse.url, source.config, context,
            )
            if (details.isWebFile) throw SourceOperationUnsupportedException()
            if (source.config.ruleToc == null) return@withRuntime details.manga.toSourceContent()
            val tocUrl = details.tocUrl?.takeIf(String::isNotBlank) ?: detailsResponse.url
            val chapters = readToc(source, executor, context, tocUrl, detailsResponse)
            details.manga.toSourceContent().copy(chapters = chapters)
        }
    }

    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent? {
        val source = source(chapter.source.name)
        return withRuntime(source) { runtime, executor ->
            val context = runtime.createRuntimeContext()
            context.setChapter(
                org.skepsun.kototoro.core.javascript.ChapterInfo(chapter.url, chapter.title.orEmpty(), chapter.number.toInt()),
            )
            val queue = ArrayDeque<String>().apply { add(chapter.url) }
            val visited = linkedSetOf<String>()
            val contents = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val url = queue.removeFirst()
                if (!visited.add(url)) continue
                if (nextChapterUrl != null && url.substringBefore(',') == nextChapterUrl.substringBefore(',')) continue
                require(visited.size <= MAX_PAGES) { "Legado content exceeds $MAX_PAGES pages" }
                val response = fetch(source, executor, context, url, source.config.ruleContent?.webView == "true")
                val page = BookContent.parseNovelPageWithRuntimeContext(
                    response.body, response.url, source.parserSource, source.config, context, nextChapterUrl,
                )
                if (page.content.isNotBlank()) contents += page.content
                page.nextPageUrls.forEach { queue.add(LegadoUrlRules.resolveUrl(response.url, it)) }
            }
            val text = BookContent.finalizeNovelChapter(contents, chapter.url, source.config, context, nextChapterUrl)
            text.takeIf(String::isNotBlank)?.let { SourceChapterContent(Entities.escape(it)) }
        }
    }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> =
        throw SourceOperationUnsupportedException()

    override suspend fun getPageUrl(page: SourcePage): String = page.url

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = emptyList()

    private fun exploreKinds(source: DesktopLegadoSource, context: StandaloneLegadoRuleRuntimeContext) =
        LegadoExploreKinds.parse(source.config.exploreUrl, context, source.config.bookSourceUrl)

    private suspend fun readToc(
        source: DesktopLegadoSource,
        executor: LegadoHttpExecutor,
        context: StandaloneLegadoRuleRuntimeContext,
        tocUrl: String,
        detailsResponse: LegadoHttpResponse,
    ): List<SourceChapter> {
        val queue = ArrayDeque<String>().apply { add(tocUrl) }
        val visited = linkedSetOf<String>()
        val chapters = linkedMapOf<String, org.skepsun.kototoro.parsers.model.ContentChapter>()
        var reverse = false
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue
            require(visited.size <= MAX_PAGES) { "Legado table of contents exceeds $MAX_PAGES pages" }
            val response = if (url == detailsResponse.url) detailsResponse
            else fetch(source, executor, context, url, source.config.ruleToc?.webView == true)
            val page = BookChapterList.parseWithRuntimeContext(response.body, response.url, source.parserSource, source.config, context)
            page.chapters.forEach { chapters.putIfAbsent(it.url, it) }
            reverse = reverse || page.shouldReverse
            page.nextPageUrls.forEach { queue.add(LegadoUrlRules.resolveUrl(response.url, it)) }
        }
        val ordered = chapters.values.toList().let { if (reverse) it.reversed() else it }
        return ordered.mapIndexed { index, chapter -> chapter.copy(number = index + 1f).toSourceChapter() }
    }

    private suspend fun fetch(
        source: DesktopLegadoSource,
        executor: LegadoHttpExecutor,
        context: StandaloneLegadoRuleRuntimeContext,
        url: String,
        webView: Boolean,
    ): LegadoHttpResponse {
        return execute(executor, context, requestPlan(source, context, url, webView = webView))
    }

    private fun requestPlan(
        source: DesktopLegadoSource,
        context: StandaloneLegadoRuleRuntimeContext,
        url: String,
        key: String? = null,
        page: Int = 1,
        webView: Boolean = false,
    ): LegadoRequestPlan = LegadoRequestPlanner(
            key = key,
            page = page,
            baseUrl = source.config.bookSourceUrl,
            jsEvaluator = context.asJsEvaluator { source.config.bookSourceUrl },
            enabledCookieJarDefault = source.config.enabledCookieJar != false,
            useWebViewDefault = webView,
            webJsDefault = null,
            webViewDelayDefault = 0,
            defaultHeaders = emptyMap(),
        ).prepare(url).plan

    private suspend fun execute(
        executor: LegadoHttpExecutor,
        context: StandaloneLegadoRuleRuntimeContext,
        plan: LegadoRequestPlan,
    ): LegadoHttpResponse {
        val response = executor.execute(plan)
        return plan.bodyJs?.takeIf(String::isNotBlank)?.let { script ->
            response.copy(body = context.evalJs(script, response.body, response.url).toString())
        } ?: response
    }

    private suspend fun source(name: String): DesktopLegadoSource =
        sources().firstOrNull { it.id == name && it.config.bookSourceType == 0 } ?: throw SourceUnavailableException(name)

    private suspend fun <T> withRuntime(
        source: DesktopLegadoSource,
        block: suspend (StandaloneLegadoListRuntime, LegadoHttpExecutor) -> T,
    ): T {
        val engine = createEngine(source)
        return try {
            val executor = createExecutor(source)
            block(StandaloneLegadoListRuntime(engine, source.config, source.id, variableStore, executor), executor)
        } catch (timeout: DesktopLegadoScriptTimeout) {
            // An Error on purpose inside Rhino (scripts cannot catch it); callers of a source expect an exception.
            throw IllegalStateException("Legado script timed out", timeout)
        } finally {
            engine.dispose()
        }
    }

    companion object {
        private const val MAX_PAGES = 100
        private const val CATEGORY_PREFIX = "category:"
        fun owns(name: String): Boolean = name.startsWith("JSON_LEGADO_")
    }
}

internal data class DesktopLegadoSource(val id: String, val config: LegadoBookSource) {
    val ref: SourceRef get() = SourceRef(id, "", "NOVEL")
    val parserSource: ContentSource get() = object : ContentSource {
        override val name = id
        override val locale = ""
        override val contentType = ContentType.NOVEL
    }
}
