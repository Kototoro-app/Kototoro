package org.skepsun.kototoro.core.db

import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState

/**
 * Data-only filter conditions understood by the SQL query builders and shared list derivers.
 *
 * It mirrors the app's UI-flavoured `ListFilterOption` (which also carries resource ids, icons and display
 * names); the app maps its options to criteria at the repository/derivation boundary, so shared code does not
 * depend on any UI type.
 */
sealed interface ListFilterCriteria {

    val groupKey: String

    data object Downloaded : ListFilterCriteria {
        override val groupKey: String get() = "_downloaded"
    }

    enum class Macro : ListFilterCriteria {
        COMPLETED, NEW_CHAPTERS, FAVORITE, NSFW;

        override val groupKey: String get() = name
    }

    data class Branch(val titleText: String?, val chaptersCount: Int) : ListFilterCriteria {
        override val groupKey: String get() = "_branch"
    }

    /** SQL uses [tagId]; history snapshots preserve their legacy title + key matching. */
    data class Tag(val tagId: Long, val title: String? = null, val key: String? = null) : ListFilterCriteria {
        override val groupKey: String get() = "_tag"
    }

    data class Favorite(val categoryId: Long) : ListFilterCriteria {
        override val groupKey: String get() = "_favcat"
    }

    data class Source(val mangaSource: ContentSource) : ListFilterCriteria {
        override val groupKey: String get() = "_source"
    }

    data class PublicationState(val state: ContentState) : ListFilterCriteria {
        override val groupKey: String get() = "_publication_state"
    }

    data class ReadingStatus(val statusName: String) : ListFilterCriteria {
        override val groupKey: String get() = "_reading_status"
    }

    data class Inverted(val option: ListFilterCriteria) : ListFilterCriteria {
        override val groupKey: String get() = "_inv" + option.groupKey
    }
}
