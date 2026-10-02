package org.koitharu.kotatsu.parsers

public object ErrorMessages {

    public const val FILTER_MULTIPLE_STATES_NOT_SUPPORTED: String = "Multiple states are not supported by this source"
    public const val FILTER_MULTIPLE_GENRES_NOT_SUPPORTED: String = "Multiple genres are not supported by this source"
    public const val FILTER_MULTIPLE_CONTENT_RATING_NOT_SUPPORTED: String =
        "Multiple Content ratings are not supported by this source"
    public const val FILTER_MULTIPLE_CONTENT_TYPES_NOT_SUPPORTED: String =
        "Multiple Content types are not supported by this source"
    public const val FILTER_MULTIPLE_DEMOGRAPHICS_NOT_SUPPORTED: String =
        "Multiple Demographics are not supported by this source"
    public const val FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED: String =
        "Filtering by both genres and locale is not supported by this source"
    public const val FILTER_BOTH_STATES_GENRES_NOT_SUPPORTED: String =
        "Filtering by both genres and states is not supported by this source"
    public const val SEARCH_NOT_SUPPORTED: String = "Search is not supported by this source"
}
