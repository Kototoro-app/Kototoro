package org.skepsun.kototoro.main.ui.navigation3

import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder

interface MainNavigator {
    fun openTopLevel(key: TopLevelNavKey)

    fun openContentList(
        source: ContentSource,
        filter: ContentListFilter? = null,
        sortOrder: SortOrder? = null,
    )

    fun openDetails(
        content: Content,
        sharedElementKey: String? = null,
    )

    fun openDetails(
        origin: DetailsOrigin,
        sharedElementKey: String? = null,
    )

    fun pop(): Boolean
}
