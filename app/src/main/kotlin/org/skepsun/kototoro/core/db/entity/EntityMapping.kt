package org.skepsun.kototoro.core.db.entity

import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.looksLikeNovelUrl
import org.skepsun.kototoro.core.model.looksLikeVideoUrl
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.parsers.util.toArraySet
import org.skepsun.kototoro.parsers.util.toTitleCase

private const val VALUES_DIVIDER = '\n'

// Entity to model

fun TagEntity.toContentTag() = ContentTag(
    key = this.key,
    title = this.title.toTitleCase(),
    source = ContentSource(this.source),
)

fun Collection<TagEntity>.toContentTags() = mapToSet(TagEntity::toContentTag)

fun Collection<TagEntity>.toContentTagsList() = map(TagEntity::toContentTag)

fun MangaEntity.toContent(tags: Set<ContentTag>, chapters: List<ChapterEntity>?): Content {
    val persistedContentType = this.contentType?.let { name ->
        runCatching { ContentType.valueOf(name) }.getOrNull()
    }
    val persistedSource = ContentSource(this.source, persistedContentType)
    val persistedChapters = chapters?.toContentChapters(persistedContentType)
    val resolvedSource = when {
        persistedSource == LocalMangaSource && (
            persistedContentType == ContentType.VIDEO ||
                persistedContentType == ContentType.HENTAI_VIDEO ||
                url.looksLikeVideoUrl() ||
                publicUrl.looksLikeVideoUrl() ||
                persistedChapters?.any { it.url.looksLikeVideoUrl() } == true
            ) -> LocalVideoSource

        persistedSource == LocalMangaSource && (
            persistedContentType == ContentType.NOVEL ||
                persistedContentType == ContentType.HENTAI_NOVEL ||
                url.looksLikeNovelUrl() ||
                publicUrl.looksLikeNovelUrl() ||
                persistedChapters?.any { it.url.looksLikeNovelUrl() } == true
            ) -> LocalNovelSource

        else -> persistedSource
    }
    return Content(
        id = this.id,
        title = this.title,
        altTitles = this.altTitles?.split(VALUES_DIVIDER)?.toArraySet().orEmpty(),
        state = this.state?.let { ContentState(it) },
        rating = this.rating,
        contentRating = ContentRating(this.contentRating)
            ?: if (isNsfw) ContentRating.ADULT else null,
        url = this.url,
        publicUrl = this.publicUrl,
        coverUrl = this.coverUrl,
        largeCoverUrl = this.largeCoverUrl,
        authors = this.authors?.split(VALUES_DIVIDER)?.toArraySet().orEmpty(),
        source = resolvedSource,
        tags = tags.mapToSet { it.copy(source = resolvedSource) },
        chapters = persistedChapters?.map { chapter ->
            if ((resolvedSource == LocalVideoSource || resolvedSource == LocalNovelSource) &&
                chapter.source == LocalMangaSource
            ) {
                chapter.copy(source = resolvedSource)
            } else {
                chapter
            }
        },
        description = this.description,
        sourceData = this.sourceData,
    )
}
fun MangaWithTags.toContent(chapters: List<ChapterEntity>? = null) = manga.toContent(tags.toContentTags(), chapters)

fun Collection<MangaWithTags>.toContentList() = map { it.toContent() }

fun ChapterEntity.toContentChapter(fallbackContentType: ContentType? = null) = ContentChapter(
    id = chapterId,
    title = title.nullIfEmpty(),
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = ContentSource(source, fallbackContentType),
    sourceData = sourceData,
)

fun Collection<ChapterEntity>.toContentChapters(fallbackContentType: ContentType? = null) =
    map { it.toContentChapter(fallbackContentType) }

// Model to entity

fun Content.toEntity() = MangaEntity(
    id = id,
    url = url,
    publicUrl = publicUrl,
    source = source.name,
    largeCoverUrl = largeCoverUrl,
    coverUrl = coverUrl.orEmpty(),
    altTitles = altTitles.joinToString(VALUES_DIVIDER.toString()),
    rating = rating,
    isNsfw = isNsfw(),
    contentRating = contentRating?.name,
    state = state?.name,
    title = title,
    authors = authors.joinToString(VALUES_DIVIDER.toString()),
    description = description,
    contentType = source.resolvedContentTypeForSnapshot()?.name,
    sourceData = sourceData,
)

fun ContentTag.toEntity() = TagEntity(
    title = title,
    key = key,
    source = source.name,
    id = "${key}_${source.name}".longHashCode(),
    isPinned = false, // for future use
)

fun Collection<ContentTag>.toEntities() = map(ContentTag::toEntity)

fun Iterable<IndexedValue<ContentChapter>>.toEntities(mangaId: Long) = map { (index, chapter) ->
    ChapterEntity(
        chapterId = chapter.id,
        mangaId = mangaId,
        title = chapter.title.orEmpty(),
        number = chapter.number,
        volume = chapter.volume,
        url = chapter.url,
        scanlator = chapter.scanlator,
        uploadDate = chapter.uploadDate,
            branch = chapter.branch,
            source = chapter.source.name,
            index = index,
            sourceData = chapter.sourceData,
        )
}

// Other

fun SortOrder(name: String, fallback: SortOrder): SortOrder = runCatching {
    SortOrder.valueOf(name)
}.getOrDefault(fallback)

fun ContentState(name: String): ContentState? = runCatching {
    ContentState.valueOf(name)
}.getOrNull()

fun ContentRating(name: String?): ContentRating? = runCatching {
    ContentRating.valueOf(name ?: return@runCatching null)
}.getOrNull()
