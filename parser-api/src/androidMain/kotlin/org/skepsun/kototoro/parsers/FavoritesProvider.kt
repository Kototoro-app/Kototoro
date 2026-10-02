package org.skepsun.kototoro.parsers

import org.skepsun.kototoro.parsers.model.Content

/**
 * 可选能力：从站点拉取当前登录用户的收藏/书架列表。
 * 未登录或不支持时，可抛出 [org.skepsun.kototoro.parsers.exception.AuthRequiredException]
 * 或返回空列表。
 */
public interface FavoritesProvider {

    public suspend fun fetchFavorites(): List<Content>
}
