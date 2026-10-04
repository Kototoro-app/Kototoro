@file:OptIn(InternalParsersApi::class)

package org.koitharu.kotatsu.parsers.site.fixture

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*

internal class FixtureParser(context: MangaLoaderContext, source: MangaSource) : AbstractMangaParser(context, source) {

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RATING)
    override val filterCapabilities = MangaListFilterCapabilities(isMultipleTagsSupported = true, isSearchSupported = true)
    override val configKeyDomain = ConfigKey.Domain("fixture.example", "mirror.example")

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> =
        if (offset >= 4) emptyList() else (0 until 2).map { index ->
            val position = offset + index
            Manga(
                id = 2_000L + position, title = "${source.name} ${order.name} q=${filter.query.orEmpty()} t=${filter.tags.size} #$position",
                altTitles = emptySet(), url = "/title/$position", publicUrl = "https://$domain/title/$position", rating = 0.5f,
                contentRating = null, coverUrl = "http://$domain/cover/$position.png", tags = emptySet(), state = MangaState.ONGOING,
                authors = setOf("Fixture Author"), source = source,
            )
        }

    override suspend fun getDetails(manga: Manga): Manga = manga.copy(
        description = "Fixture details for ${manga.title}",
        chapters = (1..2).map { number ->
            MangaChapter(
                id = manga.id * 10 + number, title = "Chapter $number", number = number.toFloat(), volume = 0,
                url = "${manga.url}/chapter/$number", scanlator = null, uploadDate = 1_700_000_000_000L + number, branch = null,
                source = source,
            )
        },
    )

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = if (chapter.title == "Chapter 2") {
        // Text-only chapters travel as HTML data: URLs; the host assembles them when there is no chapter body call.
        listOf("<p>First</p>", "<p>Second</p>").mapIndexed { index, html ->
            MangaPage(
                id = chapter.id * 100 + index,
                url = "data:text/html;base64," + java.util.Base64.getEncoder().encodeToString(html.toByteArray()),
                preview = null, source = source,
            )
        }
    } else (0 until 3).map { index ->
        MangaPage(id = chapter.id * 100 + index, url = "http://$domain/img/${chapter.id}/$index.png", preview = null, source = source)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = setOf(MangaTag("Action", "action", source), MangaTag("Drama", "drama", source)),
    )

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Fixture-Arch", "kotatsu").build())
}
