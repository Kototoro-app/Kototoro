package org.skepsun.kototoro.list.ui.config

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

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
