package org.skepsun.kototoro.core.parser.kotatsu

import org.koitharu.kotatsu.parsers.model.ContentRating as KTContentRating
import org.koitharu.kotatsu.parsers.model.ContentType as KTContentType
import org.koitharu.kotatsu.parsers.model.Demographic as KTDemographic
import org.koitharu.kotatsu.parsers.model.Favicon as KTFavicon
import org.koitharu.kotatsu.parsers.model.Favicons as KTFavicons
import org.koitharu.kotatsu.parsers.model.Manga as KTManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as KTMangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter as KTContentListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities as KTMangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions as KTMangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage as KTMangaPage
import org.koitharu.kotatsu.parsers.model.MangaState as KTMangaState
import org.koitharu.kotatsu.parsers.model.SortOrder as KTSortOrder
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.Favicon
import org.skepsun.kototoro.parsers.model.Favicons
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder

internal fun KTContentType.toKototoro(): ContentType = when (this) {
    KTContentType.MANGA -> ContentType.MANGA
    KTContentType.MANHWA -> ContentType.MANHWA
    KTContentType.MANHUA -> ContentType.MANHUA
    KTContentType.HENTAI -> ContentType.HENTAI_MANGA
    KTContentType.COMICS -> ContentType.COMICS
    KTContentType.NOVEL -> ContentType.NOVEL
    KTContentType.ONE_SHOT -> ContentType.ONE_SHOT
    KTContentType.DOUJINSHI -> ContentType.DOUJINSHI
    KTContentType.IMAGE_SET -> ContentType.IMAGE_SET
    KTContentType.ARTIST_CG -> ContentType.ARTIST_CG
    KTContentType.GAME_CG -> ContentType.GAME_CG
    KTContentType.OTHER -> ContentType.OTHER
}

internal fun ContentType.toKotatsu(): KTContentType = when (this) {
    ContentType.MANGA -> KTContentType.MANGA
    ContentType.MANHWA -> KTContentType.MANHWA
    ContentType.MANHUA -> KTContentType.MANHUA
    ContentType.HENTAI_MANGA,
    ContentType.HENTAI_NOVEL,
    ContentType.HENTAI_VIDEO -> KTContentType.HENTAI
    ContentType.COMICS -> KTContentType.COMICS
    ContentType.NOVEL -> KTContentType.NOVEL
    ContentType.VIDEO -> KTContentType.MANGA
    ContentType.ONE_SHOT -> KTContentType.ONE_SHOT
    ContentType.DOUJINSHI -> KTContentType.DOUJINSHI
    ContentType.IMAGE_SET -> KTContentType.IMAGE_SET
    ContentType.ARTIST_CG -> KTContentType.ARTIST_CG
    ContentType.GAME_CG -> KTContentType.GAME_CG
    ContentType.OTHER -> KTContentType.OTHER
}

internal fun KTContentRating.toKototoro(): ContentRating = when (this) {
    KTContentRating.SAFE -> ContentRating.SAFE
    KTContentRating.SUGGESTIVE -> ContentRating.SUGGESTIVE
    KTContentRating.ADULT -> ContentRating.ADULT
}

internal fun ContentRating.toKotatsu(): KTContentRating = when (this) {
    ContentRating.SAFE -> KTContentRating.SAFE
    ContentRating.SUGGESTIVE -> KTContentRating.SUGGESTIVE
    ContentRating.ADULT -> KTContentRating.ADULT
}

internal fun KTSortOrder.toKototoro(): SortOrder = when (this) {
    KTSortOrder.UPDATED -> SortOrder.UPDATED
    KTSortOrder.UPDATED_ASC -> SortOrder.UPDATED_ASC
    KTSortOrder.POPULARITY -> SortOrder.POPULARITY
    KTSortOrder.POPULARITY_ASC -> SortOrder.POPULARITY_ASC
    KTSortOrder.RATING -> SortOrder.RATING
    KTSortOrder.RATING_ASC -> SortOrder.RATING_ASC
    KTSortOrder.NEWEST -> SortOrder.NEWEST
    KTSortOrder.NEWEST_ASC -> SortOrder.NEWEST_ASC
    KTSortOrder.ALPHABETICAL -> SortOrder.ALPHABETICAL
    KTSortOrder.ALPHABETICAL_DESC -> SortOrder.ALPHABETICAL_DESC
    KTSortOrder.ADDED -> SortOrder.ADDED
    KTSortOrder.ADDED_ASC -> SortOrder.ADDED_ASC
    KTSortOrder.RELEVANCE -> SortOrder.RELEVANCE
    KTSortOrder.POPULARITY_HOUR -> SortOrder.POPULARITY_HOUR
    KTSortOrder.POPULARITY_TODAY -> SortOrder.POPULARITY_TODAY
    KTSortOrder.POPULARITY_WEEK -> SortOrder.POPULARITY_WEEK
    KTSortOrder.POPULARITY_MONTH -> SortOrder.POPULARITY_MONTH
    KTSortOrder.POPULARITY_YEAR -> SortOrder.POPULARITY_YEAR
}

internal fun SortOrder.toKotatsu(): KTSortOrder = when (this) {
    SortOrder.UPDATED -> KTSortOrder.UPDATED
    SortOrder.UPDATED_ASC -> KTSortOrder.UPDATED_ASC
    SortOrder.POPULARITY -> KTSortOrder.POPULARITY
    SortOrder.POPULARITY_ASC -> KTSortOrder.POPULARITY_ASC
    SortOrder.RATING -> KTSortOrder.RATING
    SortOrder.RATING_ASC -> KTSortOrder.RATING_ASC
    SortOrder.NEWEST -> KTSortOrder.NEWEST
    SortOrder.NEWEST_ASC -> KTSortOrder.NEWEST_ASC
    SortOrder.ALPHABETICAL -> KTSortOrder.ALPHABETICAL
    SortOrder.ALPHABETICAL_DESC -> KTSortOrder.ALPHABETICAL_DESC
    SortOrder.ADDED -> KTSortOrder.ADDED
    SortOrder.ADDED_ASC -> KTSortOrder.ADDED_ASC
    SortOrder.RELEVANCE -> KTSortOrder.RELEVANCE
    SortOrder.POPULARITY_HOUR -> KTSortOrder.POPULARITY_HOUR
    SortOrder.POPULARITY_TODAY -> KTSortOrder.POPULARITY_TODAY
    SortOrder.POPULARITY_WEEK -> KTSortOrder.POPULARITY_WEEK
    SortOrder.POPULARITY_MONTH -> KTSortOrder.POPULARITY_MONTH
    SortOrder.POPULARITY_YEAR -> KTSortOrder.POPULARITY_YEAR
}

internal fun KTDemographic.toKototoro(): Demographic = when (this) {
    KTDemographic.SHOUNEN -> Demographic.SHOUNEN
    KTDemographic.SHOUJO -> Demographic.SHOUJO
    KTDemographic.SEINEN -> Demographic.SEINEN
    KTDemographic.JOSEI -> Demographic.JOSEI
    else -> Demographic.NONE
}

internal fun Demographic.toKotatsu(): KTDemographic = when (this) {
    Demographic.SHOUNEN -> KTDemographic.SHOUNEN
    Demographic.SHOUJO -> KTDemographic.SHOUJO
    Demographic.SEINEN -> KTDemographic.SEINEN
    Demographic.JOSEI -> KTDemographic.JOSEI
    Demographic.KODOMO -> KTDemographic.KODOMO
    Demographic.NONE -> KTDemographic.NONE
}

internal fun KTMangaState?.toKototoro(): ContentState? = when (this) {
    KTMangaState.ONGOING -> ContentState.ONGOING
    KTMangaState.FINISHED -> ContentState.FINISHED
    KTMangaState.ABANDONED -> ContentState.ABANDONED
    KTMangaState.PAUSED -> ContentState.PAUSED
    KTMangaState.UPCOMING -> ContentState.UPCOMING
    KTMangaState.RESTRICTED -> ContentState.RESTRICTED
    null -> null
}

internal fun ContentState.toKotatsu(): KTMangaState = when (this) {
    ContentState.ONGOING -> KTMangaState.ONGOING
    ContentState.FINISHED -> KTMangaState.FINISHED
    ContentState.ABANDONED -> KTMangaState.ABANDONED
    ContentState.PAUSED -> KTMangaState.PAUSED
    ContentState.UPCOMING -> KTMangaState.UPCOMING
    ContentState.RESTRICTED -> KTMangaState.RESTRICTED
}

internal fun org.koitharu.kotatsu.parsers.model.MangaTag.toKototoro(source: ContentSource): org.skepsun.kototoro.parsers.model.ContentTag =
    org.skepsun.kototoro.parsers.model.ContentTag(title, key, source)

internal fun KTFavicon.toKototoro(): Favicon = Favicon(url = url, size = size, rel = null)

internal fun KTFavicons.toKototoro(): Favicons = Favicons(favicons = map { it.toKototoro() }, referer = referer)

internal fun KTManga.toKototoro(source: ContentSource): Content = Content(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.toKototoro(),
    coverUrl = coverUrl,
    tags = tags.mapTo(mutableSetOf()) { it.toKototoro(source) },
    state = state.toKototoro(),
    authors = authors,
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toKototoro(source) },
    source = source,
)

internal fun KTMangaChapter.toKototoro(source: ContentSource): ContentChapter = ContentChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = source,
)

internal fun KTMangaPage.toKototoro(source: ContentSource): ContentPage = ContentPage(
    id = id,
    url = url,
    preview = preview,
    headers = null,
    source = source,
)

internal fun KTMangaListFilterOptions.toKototoro(source: ContentSource): ContentListFilterOptions = ContentListFilterOptions(
    availableTags = availableTags.mapTo(mutableSetOf()) { it.toKototoro(source) },
    availableStates = availableStates.mapNotNullTo(mutableSetOf()) { it.toKototoro() },
    availableContentRating = availableContentRating.mapTo(mutableSetOf()) { it.toKototoro() },
    availableContentTypes = availableContentTypes.mapTo(mutableSetOf()) { it.toKototoro() },
    availableDemographics = availableDemographics.mapTo(mutableSetOf()) { it.toKototoro() },
    availableLocales = availableLocales,
)

internal fun KTContentListFilter.toKototoro(source: ContentSource): ContentListFilter = ContentListFilter(
    query = query,
    tags = tags.mapTo(mutableSetOf()) { it.toKototoro(source) },
    tagsExclude = tagsExclude.mapTo(mutableSetOf()) { it.toKototoro(source) },
    locale = locale,
    originalLocale = originalLocale,
    states = states.mapNotNullTo(mutableSetOf()) { it.toKototoro() },
    contentRating = contentRating.mapTo(mutableSetOf()) { it.toKototoro() },
    types = types.mapTo(mutableSetOf()) { it.toKototoro() },
    demographics = demographics.mapTo(mutableSetOf()) { it.toKototoro() },
    year = year,
    yearFrom = yearFrom,
    yearTo = yearTo,
    author = author,
)

internal fun KTMangaListFilterCapabilities.toKototoro(): ContentListFilterCapabilities =
    ContentListFilterCapabilities(
        isMultipleTagsSupported = isMultipleTagsSupported,
        isTagsExclusionSupported = isTagsExclusionSupported,
        isSearchSupported = isSearchSupported,
        isSearchWithFiltersSupported = isSearchWithFiltersSupported,
        isYearSupported = isYearSupported,
        isYearRangeSupported = isYearRangeSupported,
        isOriginalLocaleSupported = isOriginalLocaleSupported,
        isAuthorSearchSupported = isAuthorSearchSupported,
    )

internal fun Content.toKotatsu(source: KotatsuParserSource): KTManga = KTManga(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.toKotatsu(),
    coverUrl = coverUrl,
    tags = tags.mapTo(mutableSetOf()) { org.koitharu.kotatsu.parsers.model.MangaTag(it.title, it.key, source.delegate) },
    state = state?.toKotatsu(),
    authors = authors,
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toKotatsu(source) },
    source = source.delegate,
)

internal fun ContentChapter.toKotatsu(source: KotatsuParserSource): KTMangaChapter = KTMangaChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = source.delegate,
)

internal fun ContentPage.toKotatsu(source: KotatsuParserSource): KTMangaPage = KTMangaPage(
    id = id,
    url = url,
    preview = preview,
    source = source.delegate,
)

internal fun ContentListFilter.toKotatsu(source: KotatsuParserSource): KTContentListFilter = KTContentListFilter(
    query = query,
    tags = tags.mapTo(mutableSetOf()) { org.koitharu.kotatsu.parsers.model.MangaTag(it.title, it.key, source.delegate) },
    tagsExclude = tagsExclude.mapTo(mutableSetOf()) { org.koitharu.kotatsu.parsers.model.MangaTag(it.title, it.key, source.delegate) },
    locale = locale,
    originalLocale = originalLocale,
    states = states.mapTo(mutableSetOf<org.koitharu.kotatsu.parsers.model.MangaState>()) { it.toKotatsu() },
    contentRating = contentRating.mapTo(mutableSetOf()) { it.toKotatsu() },
    types = types.mapTo(mutableSetOf()) { it.toKotatsu() },
    demographics = demographics.mapTo(mutableSetOf()) { it.toKotatsu() },
    year = year,
    yearFrom = yearFrom,
    yearTo = yearTo,
    author = author,
)
