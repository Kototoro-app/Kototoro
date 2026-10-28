package org.skepsun.kototoro.history.domain

/**
 * The date header a history row falls under, as Android's history list groups it (`calculateTimeAgo`): just now,
 * today, yesterday, a few days ago, then the date itself. Platforms supply the local calendar day and format the label.
 */
sealed interface HistoryDateGroup {
    data object JustNow : HistoryDateGroup
    data object Today : HistoryDateGroup
    data object Yesterday : HistoryDateGroup
    data class DaysAgo(val days: Int) : HistoryDateGroup
    /** Six days or more: the row's own local date, as a day count since 1970-01-01. */
    data class Absolute(val epochDay: Long) : HistoryDateGroup
}

/**
 * [itemEpochDay] and [todayEpochDay] are local calendar days; [minutesAgo] is the time since the row's instant. A row
 * dated in the future has no header (null), as on Android.
 */
fun historyDateGroup(itemEpochDay: Long, todayEpochDay: Long, minutesAgo: Long): HistoryDateGroup? =
    when (val days = todayEpochDay - itemEpochDay) {
        in Long.MIN_VALUE..-1L -> null
        0L -> if (minutesAgo < 3) HistoryDateGroup.JustNow else HistoryDateGroup.Today
        1L -> HistoryDateGroup.Yesterday
        in 2L..5L -> HistoryDateGroup.DaysAgo(days.toInt())
        else -> HistoryDateGroup.Absolute(itemEpochDay)
    }
