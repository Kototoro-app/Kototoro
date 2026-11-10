package org.skepsun.kototoro.space.domain

import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.parsers.model.ContentType

@JvmInline
value class SpaceId(val value: String)

enum class SpaceKind {
    MANGA,
    NOVEL,
    ANIME,
    ALL,
}

data class SpaceContext(
    val id: SpaceId,
    val kind: SpaceKind,
    val allowedContentTypes: Set<ContentType>,
    val title: String? = null,
    val sourceLanguages: Set<String> = emptySet(),
    val sourceKinds: Set<SourceType> = emptySet(),
    val isBuiltIn: Boolean = true,
    val sortKey: Int = 0,
    val enabled: Boolean = true,
)

object BuiltInSpaces {

    val Manga = SpaceId("builtin:manga")
    val Novel = SpaceId("builtin:novel")
    val Anime = SpaceId("builtin:anime")

    val contexts: List<SpaceContext> = listOf(
        SpaceContext(
            id = Manga,
            kind = SpaceKind.MANGA,
            allowedContentTypes = setOf(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
                ContentType.HENTAI_MANGA,
                ContentType.ONE_SHOT,
                ContentType.DOUJINSHI,
                ContentType.IMAGE_SET,
                ContentType.ARTIST_CG,
                ContentType.GAME_CG,
            ),
            sortKey = 0,
        ),
        SpaceContext(
            id = Novel,
            kind = SpaceKind.NOVEL,
            allowedContentTypes = setOf(
                ContentType.NOVEL,
                ContentType.HENTAI_NOVEL,
            ),
            sortKey = 1,
        ),

    )
}

private val NOVEL_CONTENT_TYPES = setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
private val VIDEO_CONTENT_TYPES = setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)

fun Set<ContentType>.primarySpaceKind(): SpaceKind = when {
    isEmpty() -> SpaceKind.ALL
    all { it in NOVEL_CONTENT_TYPES } -> SpaceKind.NOVEL
    all { it in VIDEO_CONTENT_TYPES } -> SpaceKind.ANIME
    all { it !in NOVEL_CONTENT_TYPES && it !in VIDEO_CONTENT_TYPES } -> SpaceKind.MANGA
    else -> SpaceKind.ALL
}
