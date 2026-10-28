package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.history.domain.HistoryDateGroup
import org.skepsun.kototoro.history.domain.historyDateGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Android's history headers (HistoryListViewModel.headerFor) for the desktop orders: the time orders group by the
 * date bucket shared in core-domain, the progress order by reading status, and the title order not at all.
 */
internal fun historyGroupOf(
    order: DesktopLibraryOrder,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ((DesktopLibraryEntry) -> String?)? = when (order) {
    DesktopLibraryOrder.RECENT -> { entry -> historyDateLabel(entry.updatedAt, now, zone) }
    DesktopLibraryOrder.LAST_READ -> { entry -> historyDateLabel(entry.lastReadAt ?: entry.updatedAt, now, zone) }
    DesktopLibraryOrder.PROGRESS -> { entry ->
        val percent = entry.progressPercent ?: -1f
        AndroidStrings[when {
            ReadingProgress.isCompleted(percent) -> "status_completed"
            percent in 0f..0.01f -> "status_planned"
            percent in 0f..1f -> "status_reading"
            else -> "unknown"
        }]
    }
    DesktopLibraryOrder.TITLE -> null
}

internal fun historyDateLabel(epochMillis: Long, now: Instant, zone: ZoneId): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val day = instant.atZone(zone).toLocalDate()
    val group = historyDateGroup(day.toEpochDay(), now.atZone(zone).toLocalDate().toEpochDay(),
        java.time.Duration.between(instant, now).toMinutes())
    return when (group) {
        null -> AndroidStrings["unknown"]
        HistoryDateGroup.JustNow -> AndroidStrings["just_now"]
        HistoryDateGroup.Today -> AndroidStrings["today"]
        HistoryDateGroup.Yesterday -> AndroidStrings["yesterday"]
        is HistoryDateGroup.DaysAgo -> desktopText("history.daysAgo", group.days)
        is HistoryDateGroup.Absolute -> LocalDate.ofEpochDay(group.epochDay)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(DesktopStrings.locale))
    }
}
