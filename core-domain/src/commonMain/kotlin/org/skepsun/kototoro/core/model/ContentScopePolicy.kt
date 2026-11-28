package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.ContentType

/**
 * 本构建的内容范围策略：只保留漫画能力。
 *
 * 小说与视频的类型在数据层依然保留（旧数据库、备份还原、第三方扩展都可能带入 NOVEL / VIDEO 内容），
 * 但产品面统一通过这里判定是否放行，避免散落在各处的零散判断出现不一致。
 * 这样处理的好处是：即使某个来源或历史记录仍是小说/视频，它也不会出现在界面、来源列表或本地库中。
 */
object ContentScopePolicy {

    /** 允许展示的内容类型。 */
    val supportedTypes: Set<ContentType> = setOf(
        ContentType.MANGA,
        ContentType.MANHWA,
        ContentType.MANHUA,
        ContentType.COMICS,
        ContentType.ONE_SHOT,
        ContentType.DOUJINSHI,
        ContentType.IMAGE_SET,
        ContentType.ARTIST_CG,
        ContentType.GAME_CG,
        ContentType.HENTAI_MANGA,
        ContentType.OTHER,
    )

    /**
     * 归入书架空间的内容类型。
     *
     * [ContentType.OTHER] 不在这里：未声明类型的来源仍按漫画放行（见 [isSupported]），但它本身不该
     * 成为一个空间的成员，否则 `spaceFor(OTHER)` 的空值约定就和空间的允许集合自相矛盾。
     */
    val spaceTypes: Set<ContentType> = supportedTypes - ContentType.OTHER

    /** 小说类型，仅用于识别并拦截。 */
    val novelTypes: Set<ContentType> = setOf(
        ContentType.NOVEL,
        ContentType.HENTAI_NOVEL,
    )

    /** 视频类型，仅用于识别并拦截。 */
    val videoTypes: Set<ContentType> = setOf(
        ContentType.VIDEO,
        ContentType.HENTAI_VIDEO,
    )

    /**
     * 该内容类型是否允许在本构建中展示。
     * 未声明类型的来源（null）按漫画处理，因为大量 JSON 源不声明类型。
     */
    fun isSupported(type: ContentType?): Boolean = type == null || type in supportedTypes

    /** 是否属于被移除的小说/视频能力。 */
    fun isRemoved(type: ContentType?): Boolean = type != null && (type in novelTypes || type in videoTypes)
}
