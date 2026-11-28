package org.skepsun.kototoro.space.domain

import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.ContentScopePolicy
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

    /**
     * 小说与动画空间已从本构建移除，这里保留 id 常量只是为了让旧设置、备份与
     * 遗留引用仍能解析，不再出现在 [contexts] 中，因此也不会进入空间切换器或导航栈。
     */
    val Novel = SpaceId("builtin:novel")
    val Anime = SpaceId("builtin:anime")

    val contexts: List<SpaceContext> = listOf(
        SpaceContext(
            id = Manga,
            kind = SpaceKind.MANGA,
            allowedContentTypes = ContentScopePolicy.spaceTypes,
            sortKey = 0,
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
