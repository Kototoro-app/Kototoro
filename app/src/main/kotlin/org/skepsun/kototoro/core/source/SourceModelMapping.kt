package org.skepsun.kototoro.core.source

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentExternalTrack
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.EbookFormat
import java.util.Locale

internal fun ContentSource.toSourceRef() = SourceRef(name, locale, contentType.name)

internal fun ContentTag.toSourceTag() = SourceTag(title, key, source.toSourceRef())

internal fun ContentTagGroup.toSourceTagGroup() =
    SourceTagGroup(title, tags.mapTo(linkedSetOf()) { it.toSourceTag() }, isExclusive)

internal fun Content.toSourceContent() = SourceContent(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.name,
    coverUrl = coverUrl,
    tags = tags.mapTo(linkedSetOf()) { it.toSourceTag() },
    state = state?.name,
    authors = authors,
    source = source.toSourceRef(),
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toSourceChapter() },
    sourceData = sourceData,
)

internal fun ContentChapter.toSourceChapter() = SourceChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = source.toSourceRef(),
    sourceData = sourceData,
    ebookFormats = ebookFormats.map { it.name },
)

internal fun ContentPage.toSourcePage() = SourcePage(
    id = id,
    url = url,
    preview = preview,
    source = source.toSourceRef(),
    headers = headers,
    externalSubtitleTracks = externalSubtitleTracks.map { SourceExternalTrack(it.url, it.lang, it.headers) },
    playbackLabel = playbackLabel,
    playbackQuality = playbackQuality,
)

internal fun SourceTag.toParser(resolve: (String) -> ContentSource) = ContentTag(title, key, resolve(source.name))

internal fun SourceContent.toParser(resolve: (String) -> ContentSource) = Content(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.let { parserEnum<ContentRating>(it) },
    coverUrl = coverUrl,
    tags = tags.mapTo(linkedSetOf()) { it.toParser(resolve) },
    state = state?.let { parserEnum<ContentState>(it) },
    authors = authors,
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toParser(resolve) },
    source = resolve(source.name),
    sourceData = sourceData,
)

internal fun SourceChapter.toParser(resolve: (String) -> ContentSource) = ContentChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = resolve(source.name),
    sourceData = sourceData,
    ebookFormats = ebookFormats.map { parserEnum<EbookFormat>(it) },
)

internal fun SourcePage.toParser(resolve: (String) -> ContentSource): ContentPage {
    // The Android parser ABI has no native Page context field; never silently drop host-owned image state.
    if (requestContext != null) throw SourceOperationUnsupportedException()
    return ContentPage(
        id = id,
        url = url,
        preview = preview,
        headers = headers,
        externalSubtitleTracks = externalSubtitleTracks.map { ContentExternalTrack(it.url, it.lang, it.headers) },
        playbackLabel = playbackLabel,
        playbackQuality = playbackQuality,
        source = resolve(source.name),
    )
}

internal fun SourceFilter.toParser(resolve: (String) -> ContentSource): ContentListFilter {
    if (dynamicFilters.isNotEmpty()) throw SourceOperationUnsupportedException()
    return ContentListFilter(
        query = query,
        tags = tags.mapTo(linkedSetOf()) { it.toParser(resolve) },
        tagsExclude = tagsExclude.mapTo(linkedSetOf()) { it.toParser(resolve) },
        locale = locale?.let(Locale::forLanguageTag),
        originalLocale = originalLocale?.let(Locale::forLanguageTag),
        states = states.mapTo(linkedSetOf()) { parserEnum<ContentState>(it) },
        contentRating = contentRating.mapTo(linkedSetOf()) { parserEnum<ContentRating>(it) },
        types = types.mapTo(linkedSetOf()) { parserEnum<ContentType>(it) },
        demographics = demographics.mapTo(linkedSetOf()) { parserEnum<Demographic>(it) },
        year = year,
        yearFrom = yearFrom,
        yearTo = yearTo,
        author = author,
    )
}

internal fun ContentListFilterOptions.toSourceFilterOptions() = SourceFilterOptions(
    availableTags = availableTags.mapTo(linkedSetOf()) { it.toSourceTag() },
    tagGroups = tagGroups.map { it.toSourceTagGroup() },
    availableStates = availableStates.mapTo(linkedSetOf()) { it.name },
    availableContentRating = availableContentRating.mapTo(linkedSetOf()) { it.name },
    availableContentTypes = availableContentTypes.mapTo(linkedSetOf()) { it.name },
    availableDemographics = availableDemographics.mapTo(linkedSetOf()) { it.name },
    availableLocales = availableLocales.mapTo(linkedSetOf()) { it.toLanguageTag() },
    effectiveTagGroups = effectiveTagGroups.map { it.toSourceTagGroup() },
)

internal fun ContentListFilterCapabilities.toSourceCapabilities() = SourceFilterCapabilities(
    isMultipleTagsSupported = isMultipleTagsSupported,
    isTagsExclusionSupported = isTagsExclusionSupported,
    isSearchSupported = isSearchSupported,
    isSearchWithFiltersSupported = isSearchWithFiltersSupported,
    isYearSupported = isYearSupported,
    isYearRangeSupported = isYearRangeSupported,
    isOriginalLocaleSupported = isOriginalLocaleSupported,
    isAuthorSearchSupported = isAuthorSearchSupported,
)

internal inline fun <reified T : Enum<T>> parserEnum(name: String): T = try {
    enumValueOf<T>(name)
} catch (_: IllegalArgumentException) {
    throw SourceInvalidArgumentException()
}
