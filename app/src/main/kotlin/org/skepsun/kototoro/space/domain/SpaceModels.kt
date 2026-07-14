package org.skepsun.kototoro.space.domain

import org.skepsun.kototoro.parsers.model.ContentType

@JvmInline
value class SpaceId(val value: String)

enum class SpaceKind {
    MANGA,
    NOVEL,
    ANIME,
}

data class SpaceContext(
    val id: SpaceId,
    val kind: SpaceKind,
    val allowedContentTypes: Set<ContentType>,
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
        ),
        SpaceContext(
            id = Novel,
            kind = SpaceKind.NOVEL,
            allowedContentTypes = setOf(
                ContentType.NOVEL,
                ContentType.HENTAI_NOVEL,
            ),
        ),
        SpaceContext(
            id = Anime,
            kind = SpaceKind.ANIME,
            allowedContentTypes = setOf(
                ContentType.VIDEO,
                ContentType.HENTAI_VIDEO,
            ),
        ),
    )
}
