package org.skepsun.kototoro.list.ui.config

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** The page whose own display options this section edits; null for the general ones and the home rails. */
fun ListConfigSection?.toDisplaySection(): org.skepsun.kototoro.list.ui.ListDisplaySection? = when (this) {
    is ListConfigSection.Favorites -> org.skepsun.kototoro.list.ui.ListDisplaySection.FAVORITES
    ListConfigSection.History -> org.skepsun.kototoro.list.ui.ListDisplaySection.HISTORY
    ListConfigSection.Suggestions -> org.skepsun.kototoro.list.ui.ListDisplaySection.SUGGESTIONS
    ListConfigSection.Updated -> org.skepsun.kototoro.list.ui.ListDisplaySection.UPDATED
    else -> null
}

sealed interface ListConfigSection : Parcelable {

    @Parcelize
    data object Home : ListConfigSection

    /** Display style of the home screen's history rail. */
    @Parcelize
    data object HomeHistory : ListConfigSection

    /** Display style of the home screen's updates rail. */
    @Parcelize
    data object HomeUpdates : ListConfigSection

    /** Display style of the home screen's recommendations rail. */
    @Parcelize
    data object HomeRecommendations : ListConfigSection

    @Parcelize
    data object History : ListConfigSection

    @Parcelize
    data object General : ListConfigSection

    @Parcelize
    data class Favorites(
        val categoryId: Long,
    ) : ListConfigSection

    @Parcelize
    data object Suggestions : ListConfigSection

    @Parcelize
    data object Updated : ListConfigSection
}
