package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.util.find

@Keep
enum class BackgroundArtworkSource(
    @StringRes val titleResId: Int,
) {
    LAST_READ(R.string.bg_artwork_source_last_read),
    LAST_FAVOURITE(R.string.bg_artwork_source_last_favourite),
    LAST_UPDATED(R.string.bg_artwork_source_last_updated),
    RANDOM_SUGGESTION(R.string.bg_artwork_source_random_suggestion),
    CUSTOM(R.string.bg_artwork_source_custom),
    ;

    companion object {
        val selectableEntries: List<BackgroundArtworkSource> = entries.toList()

        fun safeValueOf(name: String): BackgroundArtworkSource? = entries.find(name)
    }
}
