package org.skepsun.kototoro.explore.data

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

/** UI label of a sort order. The enum itself lives in :core-db and carries no Android resource ids. */
val SourcesSortOrder.titleResId: Int
    @StringRes get() = when (this) {
        SourcesSortOrder.ALPHABETIC -> R.string.by_name
        SourcesSortOrder.POPULARITY -> R.string.popular
        SourcesSortOrder.MANUAL -> R.string.manual
        SourcesSortOrder.LAST_USED -> R.string.last_used
    }
