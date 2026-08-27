package org.skepsun.kototoro.list.ui.compose

import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.list.ui.model.ContentListModel

/**
 * Shared-element identity for a logical list row.
 *
 * A metadata binding may replace the representative projection, source, title, and cover while
 * the logical entity remains the same. The list model ID is the stable identity across that
 * refresh and must therefore be the only content-derived part of the transition key.
 */
internal fun contentListSharedElementKey(
    item: ContentListModel,
    instanceKey: String?,
): String = contentCoverSharedKey(
    sourceName = "list-item",
    url = item.id.toString(),
    instanceKey = instanceKey,
)
