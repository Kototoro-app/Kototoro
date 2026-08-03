package org.skepsun.kototoro.core.parser.tsuki

import org.skepsun.kototoro.parsers.model.*

internal fun tsuki.model.ContentType.toKototoro(): ContentType = ContentType.entries.firstOrNull { it.name == name }
    ?: if (this == tsuki.model.ContentType.HENTAI) ContentType.HENTAI_MANGA else ContentType.OTHER

internal fun ContentType.toTsuki(): tsuki.model.ContentType = when (this) {
    ContentType.HENTAI_MANGA, ContentType.HENTAI_NOVEL, ContentType.HENTAI_VIDEO -> tsuki.model.ContentType.HENTAI
    ContentType.VIDEO -> tsuki.model.ContentType.MANGA
    else -> tsuki.model.ContentType.entries.firstOrNull { it.name == name } ?: tsuki.model.ContentType.OTHER
}

internal fun tsuki.model.ContentRating.toKototoro() = ContentRating.valueOf(name)
internal fun ContentRating.toTsuki() = tsuki.model.ContentRating.valueOf(name)
internal fun tsuki.model.SortOrder.toKototoro() = SortOrder.valueOf(name)
internal fun SortOrder.toTsuki() = tsuki.model.SortOrder.valueOf(name)
internal fun tsuki.model.Demographic.toKototoro() = Demographic.entries.firstOrNull { it.name == name } ?: Demographic.NONE
internal fun Demographic.toTsuki() = tsuki.model.Demographic.entries.firstOrNull { it.name == name } ?: tsuki.model.Demographic.NONE
internal fun tsuki.model.MangaState?.toKototoro() = this?.let { ContentState.valueOf(it.name) }
internal fun ContentState.toTsuki() = tsuki.model.MangaState.valueOf(name)

internal fun tsuki.model.MangaTag.toKototoro(source: ContentSource) = ContentTag(title, key, source)
internal fun ContentTag.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaTag(title, key, source)

internal fun tsuki.model.Manga.toKototoro(source: ContentSource) = Content(
    id, title, altTitles, url, publicUrl, rating, contentRating?.toKototoro(), coverUrl,
    tags.mapTo(mutableSetOf()) { it.toKototoro(source) }, state.toKototoro(), authors,
    largeCoverUrl, description, chapters?.map { it.toKototoro(source) }, source,
)

internal fun tsuki.model.MangaChapter.toKototoro(source: ContentSource) = ContentChapter(
    id, title, number, volume, url, scanlator, uploadDate, branch, source,
)

internal fun tsuki.model.MangaPage.toKototoro(source: ContentSource) = ContentPage(id, url, preview, null, source)

internal fun Content.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.Manga(
    id, title, altTitles, url, publicUrl, rating, contentRating?.toTsuki(), coverUrl,
    tags.mapTo(mutableSetOf()) { it.toTsuki(source) }, state?.toTsuki(), authors,
    largeCoverUrl, description, chapters?.map { it.toTsuki(source) }, source,
)

internal fun ContentChapter.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaChapter(
    id, title, number, volume, url, scanlator, uploadDate, branch, source,
)

internal fun ContentPage.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaPage(id, url, preview, source)

internal fun ContentListFilter.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaListFilter(
    query, tags.mapTo(mutableSetOf()) { it.toTsuki(source) },
    tagsExclude.mapTo(mutableSetOf()) { it.toTsuki(source) }, locale, originalLocale,
    states.mapTo(mutableSetOf()) { it.toTsuki() }, contentRating.mapTo(mutableSetOf()) { it.toTsuki() },
    types.mapTo(mutableSetOf()) { it.toTsuki() }, demographics.mapTo(mutableSetOf()) { it.toTsuki() },
    year, yearFrom, yearTo, author,
)

internal fun tsuki.model.MangaListFilterOptions.toKototoro(source: ContentSource) = ContentListFilterOptions(
    availableTags = availableTags.mapTo(mutableSetOf()) { it.toKototoro(source) },
    availableStates = availableStates.mapTo(mutableSetOf()) { it.toKototoro()!! },
    availableContentRating = availableContentRating.mapTo(mutableSetOf()) { it.toKototoro() },
    availableContentTypes = availableContentTypes.mapTo(mutableSetOf()) { it.toKototoro() },
    availableDemographics = availableDemographics.mapTo(mutableSetOf()) { it.toKototoro() },
    availableLocales = availableLocales,
)

internal fun tsuki.model.MangaListFilterCapabilities.toKototoro() = ContentListFilterCapabilities(
    isMultipleTagsSupported, isTagsExclusionSupported, isSearchSupported, isSearchWithFiltersSupported,
    isYearSupported, isYearRangeSupported, isOriginalLocaleSupported, isAuthorSearchSupported,
)

internal fun tsuki.model.Favicons.toKototoro() = Favicons(map { Favicon(it.url, it.size, null) }, referer)
