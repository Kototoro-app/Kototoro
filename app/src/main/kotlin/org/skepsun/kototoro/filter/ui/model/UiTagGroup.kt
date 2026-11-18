package org.skepsun.kototoro.filter.ui.model

import org.skepsun.kototoro.parsers.model.ContentTag

data class UiTagGroup(
    val title: String,
    val tags: Set<ContentTag>,
    val selected: Set<ContentTag> = emptySet(),
    val isExclusive: Boolean = false,
)

/**
 * 分类多到一屏放不下时，折叠态先显示几个标签。
 */
const val TAG_GROUP_COLLAPSED_LIMIT = 12

/**
 * 标签数量不超过这个上限就直接全部铺开（issue #683 的后续反馈：分类少的站点，
 * 比如只有 16 个分类的绅士漫画，筛选面板下方本来就有大片空位，没必要再折叠）。
 */
const val TAG_GROUP_SHOW_ALL_LIMIT = 24

/**
 * 搜索框里过滤时最多渲染的标签数量，比折叠态多，免得搜出来的结果又被截断。
 */
const val TAG_GROUP_SEARCH_LIMIT = 48

/**
 * 分类（标签）卡片一次渲染多少个标签：装得下就全显示，装不下才折叠并留出「更多」浮层。
 *
 * @param total 该卡片里可选的标签总数
 * @param searching 是否正在用搜索框过滤
 */
fun tagGroupPreviewLimit(total: Int, searching: Boolean = false): Int = when {
    searching -> TAG_GROUP_SEARCH_LIMIT
    total <= TAG_GROUP_SHOW_ALL_LIMIT -> total
    else -> TAG_GROUP_COLLAPSED_LIMIT
}

/**
 * 该卡片是否还需要「更多」浮层/搜索框（即折叠态没能把标签全铺开）。
 */
fun tagGroupNeedsExpand(total: Int): Boolean = total > TAG_GROUP_SHOW_ALL_LIMIT
