package org.skepsun.kototoro.local.domain.model

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

/**
 * 本地漫画库的阅读状态筛选。
 *
 * 与远程源的 [org.skepsun.kototoro.parsers.model.ContentListFilter] 分开管理：本地内容不是
 * "搜索条件"而是"整理方式"，所以独立持久化，避免和标签/分级等过滤条件互相覆盖。
 */
enum class LocalReadStatusFilter(@param:StringRes val titleResId: Int) {
    ALL(R.string.all),
    UNREAD(R.string.unread),
    READING(R.string.status_reading),
    COMPLETED(R.string.status_completed),
    ;

    companion object {
        fun fromName(name: String?): LocalReadStatusFilter =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}
