package org.skepsun.kototoro.explore.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

@get:StringRes
val BrowseGroupTab.titleRes: Int
    get() = when (this) {
        BrowseGroupTab.All -> R.string.all
        BrowseGroupTab.Content -> R.string.manga
        BrowseGroupTab.Novel -> R.string.novel
        BrowseGroupTab.Video -> R.string.video
    }

@get:DrawableRes
val BrowseGroupTab.iconRes: Int
    get() = when (this) {
        BrowseGroupTab.All -> R.drawable.ic_filter_content_type
        BrowseGroupTab.Content -> R.drawable.ic_content_manga
        BrowseGroupTab.Novel -> R.drawable.ic_content_novel
        BrowseGroupTab.Video -> R.drawable.ic_content_video
    }
