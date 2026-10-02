package org.skepsun.kototoro.list.domain

import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * SPIKE stand-in: the data-only part of the UI-flavoured ListFilterOption. The original carries
 * @StringRes/@DrawableRes ids, icons and display-name logic that reaches every source type in the app.
 */
sealed interface ListFilterOption {

    val groupKey: String

    data object Downloaded : ListFilterOption {
        override val groupKey: String get() = "_downloaded"
    }

    enum class Macro : ListFilterOption {
        COMPLETED, NEW_CHAPTERS, FAVORITE, NSFW;

        override val groupKey: String get() = name
    }

    data class Branch(val titleText: String?, val chaptersCount: Int) : ListFilterOption {
        override val groupKey: String get() = "_branch"
    }

    data class Tag(val tagId: Long, val tagKey: String, val title: String) : ListFilterOption {
        override val groupKey: String get() = "_tag"
    }

    data class Favorite(val categoryId: Long) : ListFilterOption {
        override val groupKey: String get() = "_favcat"
    }

    data class Source(val mangaSource: ContentSource) : ListFilterOption {
        override val groupKey: String get() = "_source"
    }

    data class PublicationState(val state: ContentState) : ListFilterOption {
        override val groupKey: String get() = "_publication_state"
    }

    data class ReadingStatus(val status: ScrobblingStatus) : ListFilterOption {
        override val groupKey: String get() = "_reading_status"
    }

    data class Inverted(val option: ListFilterOption) : ListFilterOption {
        override val groupKey: String get() = "_inv" + option.groupKey
    }
}
