package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences

/**
 * Android's services switches that have a Windows meaning today, with Android's own keys: `stats_on` decides whether
 * the reader's tracker writes statistics rows at all, `reading_time` decides whether the details page shows the
 * estimate built from them, and `related_manga` decides whether the details page asks the source for related works.
 *
 * Android keeps both in the same preferences file as everything else; Windows gives them their own store
 * (`desktop_services`) so the services page does not rewrite the appearance, library or source documents.
 */
data class DesktopServicesSettings(
    /** Android's `stats_on`: off means reading still saves history and progress, but no session or stats row lands. */
    val statsEnabled: Boolean = true,
    /** Android's `reading_time`: the details page's estimated time left, see [desktopReadingTime]. */
    val readingTimeEstimation: Boolean = true,
    /** Android's `related_manga`: the details page's 相关作品 rail, see [desktopRelatedWorks]. */
    val relatedContent: Boolean = true,
) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_STATS_ENABLED to SourcePreferenceValue.Toggle(statsEnabled),
        KEY_READING_TIME to SourcePreferenceValue.Toggle(readingTimeEstimation),
        KEY_RELATED_CONTENT to SourcePreferenceValue.Toggle(relatedContent),
    )

    companion object {
        /** Android's `AppSettings.KEY_STATS_ENABLED`. */
        const val KEY_STATS_ENABLED = "stats_on"

        /** Android's `AppSettings.KEY_READING_TIME`. */
        const val KEY_READING_TIME = "reading_time"

        /** Android's `AppSettings.KEY_RELATED_MANGA`. */
        const val KEY_RELATED_CONTENT = "related_manga"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopServicesSettings(
            // Android reads all three with a `true` default, so a fresh profile is fully switched on.
            statsEnabled = (values[KEY_STATS_ENABLED] as? SourcePreferenceValue.Toggle)?.value ?: true,
            readingTimeEstimation = (values[KEY_READING_TIME] as? SourcePreferenceValue.Toggle)?.value ?: true,
            relatedContent = (values[KEY_RELATED_CONTENT] as? SourcePreferenceValue.Toggle)?.value ?: true,
        )
    }
}

/** The stored services switches, with the same save/reload shape as the navigation and download stores. */
class DesktopServicesPreferences(private val preferences: SourcePreferences) {
    private val mutableSettings = MutableStateFlow(DesktopServicesSettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopServicesSettings> = mutableSettings.asStateFlow()

    fun current(): DesktopServicesSettings = mutableSettings.value

    fun save(value: DesktopServicesSettings): Boolean {
        val persisted = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableSettings.value = value
        return persisted
    }

    fun reload() {
        mutableSettings.value = DesktopServicesSettings.from(preferences.snapshot())
    }
}

/**
 * Android's `ReadingTime` (`details/data/ReadingTime.kt`) as far as the estimate itself goes: how long the rest of
 * the work should take, and whether that is the remainder of a started work or the whole of a fresh one.
 */
data class DesktopReadingTime(
    val hours: Int,
    val minutes: Int,
    val isContinue: Boolean,
) {
    val isEmpty: Boolean get() = hours <= 0 && minutes <= 0
}

/**
 * Android's `ReadingTimeUseCase` formula without the DI: twenty pages per chapter, the work's own seconds per page,
 * minus the progress of an unfinished read. A work with no statistics falls back to ten seconds a page, which is what
 * Android's `getSecondsPerPage` does; an estimate under a minute returns null so the row stays hidden.
 *
 * @param chapterCount every chapter of the work, the way Android sizes its `allChapters`.
 * @param percent progress of the stored history when it belongs to this work, else null.
 * @param secondsPerPage the work's measured page time, or 0 when there is nothing to measure it by.
 */
fun desktopReadingTime(
    chapterCount: Int,
    percent: Float?,
    secondsPerPage: Int,
): DesktopReadingTime? {
    if (chapterCount <= 0) return null
    val perPage = secondsPerPage.takeIf { it > 0 } ?: 10
    var total = 20 * perPage * chapterCount
    if (percent != null) {
        total = (total * (1f - percent)).toInt()
    }
    if (total < 60) return null
    return DesktopReadingTime(hours = total / 3600, minutes = (total / 60) % 60, isContinue = percent != null)
}
