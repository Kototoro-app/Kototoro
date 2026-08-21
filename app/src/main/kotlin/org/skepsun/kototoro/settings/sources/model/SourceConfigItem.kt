package org.skepsun.kototoro.settings.sources.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentSource

sealed interface SourceConfigItem : ListModel {

    data class SourceItem(
        val source: ContentSource,
        val isEnabled: Boolean,
        val isDraggable: Boolean,
        val isAvailable: Boolean,
        val isPinned: Boolean,
        val isDisableAvailable: Boolean,
    ) : SourceConfigItem {

        val isNsfw: Boolean
            get() = source.isNsfw()

        override fun areItemsTheSame(other: ListModel): Boolean {
            return other is SourceItem && other.source == source
        }
    }

    data class Tip(
        val key: String,
        @DrawableRes val iconResId: Int,
        @StringRes val textResId: Int,
    ) : SourceConfigItem {

        override fun areItemsTheSame(other: ListModel): Boolean {
            return other is Tip && other.key == key
        }
    }

    data object EmptySearchResult : SourceConfigItem {

        override fun areItemsTheSame(other: ListModel): Boolean {
            return other is EmptySearchResult
        }
    }
}
