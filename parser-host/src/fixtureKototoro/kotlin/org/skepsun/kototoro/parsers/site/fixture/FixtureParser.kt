@file:OptIn(InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.fixture

import okhttp3.Interceptor
import okhttp3.Response
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.AbstractContentParser
import org.skepsun.kototoro.parsers.model.*

internal class FixtureParser(context: ContentLoaderContext, source: ContentSource) : AbstractContentParser(context, source) {

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RATING)
    override val filterCapabilities = ContentListFilterCapabilities(isMultipleTagsSupported = true, isSearchSupported = true)
    override val configKeyDomain = ConfigKey.Domain("fixture.example", "mirror.example")

    override suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> =
        if (offset >= 4) emptyList() else (0 until 2).map { index ->
            val position = offset + index
            Content(
                id = 1_000L + position, title = "${source.name} ${order.name} q=${filter.query.orEmpty()} t=${filter.tags.size} #$position",
                altTitles = emptySet(), url = "/title/$position", publicUrl = "https://$domain/title/$position", rating = 0.5f,
                contentRating = null, coverUrl = "http://$domain/cover/$position.png", tags = emptySet(), state = ContentState.ONGOING,
                authors = setOf("Fixture Author"), source = source,
            )
        }

    override suspend fun getDetails(manga: Content): Content = manga.copy(
        description = "Fixture details for ${manga.title}",
        chapters = (1..2).map { number ->
            ContentChapter(
                id = manga.id * 10 + number, title = "Chapter $number", number = number.toFloat(), volume = 0,
                url = "${manga.url}/chapter/$number", scanlator = null, uploadDate = 1_700_000_000_000L + number, branch = null,
                source = source,
            )
        },
    )

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> = (0 until 3).map { index ->
        ContentPage(id = chapter.id * 100 + index, url = "http://$domain/img/${chapter.id}/$index.png", preview = null, source = source)
    }

    override suspend fun getFilterOptions() = ContentListFilterOptions(
        availableTags = setOf(ContentTag("Action", "action", source), ContentTag("Drama", "drama", source)),
    )

    /** A novel source answers with a whole chapter body instead of image pages. */
    override suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? =
        if (source.contentType == ContentType.NOVEL) NovelChapterContent(
            html = "<p>Body of ${chapter.title}</p><img src=\"http://$domain/illustration/${chapter.id}.png\">",
            images = listOf(NovelChapterContent.NovelImage("http://$domain/illustration/${chapter.id}.png", mapOf("X-Test" to "1"))),
        ) else null
    /** Proves the host runs every image request through the parser's interceptor. */
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Fixture-Arch", "kototoro").build())
}
