package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.library.LibraryReadingStats
import org.skepsun.kototoro.core.library.LibraryReadingStatsWork
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.stats.domain.ReadingDayStats
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind

/**
 * Android's statistics screen: the total, the streak, the activity chart, the type breakdown and the "most enjoyed"
 * list with a confirmation before the clear action. The screen takes its statistics as a parameter, so these tests pin
 * what it shows for a given set of rows without a database or a controller.
 */
class DesktopReadingStatsPageTest {

    private val source = SourceRef("MIHON_1", "zh", "MANGA")

    private fun work(id: Long, title: String, kind: StatsContentKind, duration: Long, sessions: Int, units: Int) =
        LibraryReadingStatsWork(content(id, title), kind, duration, sessions, units, 1_760_000_000_000L)

    private fun content(id: Long, title: String) = SourceContent(id, title, emptySet(), "/work/$id",
        "https://fixture.invalid/work/$id", .5f, null, null, emptySet(), "ONGOING", emptySet(), source)

    private fun stats() = LibraryReadingStats(
        works = listOf(
            work(1, "统计作品", StatsContentKind.MANGA, 3_720_000, 3, 41),
            work(2, "小说作品", StatsContentKind.NOVEL, 600_000, 1, 2),
        ),
        dailyActivity = listOf(ReadingDayStats("2025-11-29", 0, 600_000), ReadingDayStats("2025-11-30", 1, 3_720_000)),
        hourlyActivity = List(24) { if (it == 21) 3_720_000L else 0L },
        totalDuration = 4_320_000,
        activeDays = 2,
        currentStreak = 2,
        sessionCount = 4,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a library without reading time shows Android's empty state`() = runDesktopComposeUiTest {
        setContent {
            DesktopTheme {
                DesktopReadingStatsContent(stats = null, period = ReadingStatsPeriod.WEEK,
                    kind = StatsContentKind.ALL, active = true, covers = null, onBack = {}, onPeriod = {},
                    onKind = {}, onWork = {}, onClear = {})
            }
        }
        onNodeWithTag("stats-empty").assertIsDisplayed()
        onNodeWithText(AndroidStrings["empty_stats_text"]).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the summary repeats Android's numbers and the works list opens a work`() = runDesktopComposeUiTest {
        val opened = mutableListOf<Long>()
        setContent {
            DesktopTheme {
                DesktopReadingStatsContent(stats = stats(), period = ReadingStatsPeriod.WEEK,
                    kind = StatsContentKind.ALL, active = true, covers = null, onBack = {}, onPeriod = {},
                    onKind = {}, onWork = { opened += it.id }, onClear = {})
            }
        }
        onNodeWithTag("stats-total").assertIsDisplayed()
        // Android prints the same duration in hours and minutes.
        onNodeWithText(AndroidStrings.format("stats_duration_hours_minutes", 1L, 12L)).assertIsDisplayed()
        onNodeWithTag("stats-active-days").assertIsDisplayed()
        onNodeWithTag("stats-sessions").assertIsDisplayed()
        onNodeWithTag("stats-works").assertIsDisplayed()
        onNodeWithTag("stats-streak").assertIsDisplayed()
        onNodeWithTag("stats-activity-days").assertIsDisplayed()
        onNodeWithTag("stats-kinds").performScrollTo().assertIsDisplayed()
        onNodeWithTag("stats-work:1").performScrollTo().performClick()
        assertEquals(listOf(1L), opened)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the period row offers Android's five windows and reports the picked one`() = runDesktopComposeUiTest {
        val picked = mutableListOf<ReadingStatsPeriod>()
        var period by mutableStateOf(ReadingStatsPeriod.WEEK)
        setContent {
            DesktopTheme {
                DesktopReadingStatsContent(stats = stats(), period = period, kind = StatsContentKind.ALL,
                    active = true, covers = null, onBack = {}, onPeriod = { period = it; picked += it },
                    onKind = {}, onWork = {}, onClear = {})
            }
        }
        onNodeWithText(AndroidStrings["day"]).assertIsDisplayed()
        onNodeWithText(AndroidStrings["three_months"]).assertIsDisplayed()
        onNodeWithText(AndroidStrings["all_time"]).assertIsDisplayed()
        onNodeWithTag("stats-period:MONTH").performClick()
        assertEquals(listOf(ReadingStatsPeriod.MONTH), picked)
        // The picked period is the one the content now marks as selected.
        onNodeWithTag("stats-period:MONTH").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the kind row offers Android's four content kinds and reports the picked one`() = runDesktopComposeUiTest {
        val picked = mutableListOf<StatsContentKind>()
        var kind by mutableStateOf(StatsContentKind.ALL)
        setContent {
            DesktopTheme {
                DesktopReadingStatsContent(stats = stats(), period = ReadingStatsPeriod.WEEK, kind = kind,
                    active = true, covers = null, onBack = {}, onPeriod = {},
                    onKind = { kind = it; picked += it }, onWork = {}, onClear = {})
            }
        }
        onNodeWithTag("stats-kind-filter").assertIsDisplayed()
        // Android's four chips reuse the same translated labels as its kind breakdown.
        onNodeWithText(AndroidStrings["stats_kind_all"]).assertIsDisplayed()
        onNodeWithText(AndroidStrings["stats_kind_video"]).assertIsDisplayed()
        onNodeWithTag("stats-kind-option:NOVEL").performClick()
        assertEquals(listOf(StatsContentKind.NOVEL), picked)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clearing statistics asks first and reports the confirmation`() = runDesktopComposeUiTest {
        var cleared = 0
        setContent {
            DesktopTheme {
                DesktopReadingStatsContent(stats = stats(), period = ReadingStatsPeriod.WEEK,
                    kind = StatsContentKind.ALL, active = true, covers = null, onBack = {}, onPeriod = {},
                    onKind = {}, onWork = {}, onClear = { cleared++ })
            }
        }
        onNodeWithTag("stats-clear").performScrollTo().performClick()
        onNodeWithTag("stats-clear-dialog").assertIsDisplayed()
        onNodeWithText(AndroidStrings["clear_stats_confirm"]).assertIsDisplayed()
        onNodeWithTag("stats-clear-cancel").performClick()
        assertEquals(0, cleared)
        onNodeWithTag("stats-clear").performScrollTo().performClick()
        onNodeWithTag("stats-clear-accept").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `durations follow Android's thresholds`() {
        assertEquals(AndroidStrings.format("stats_duration_minutes", 42L), formatStatsDuration(42 * 60_000L))
        assertEquals(AndroidStrings.format("stats_duration_hours", 2L), formatStatsDuration(120 * 60_000L))
        assertEquals(AndroidStrings.format("stats_duration_hours_minutes", 2L, 5L),
            formatStatsDuration(125 * 60_000L))
        // A stop that never reached a minute is zero minutes, not an empty string.
        assertFalse(formatStatsDuration(59_000L).isBlank())
        // A negative duration cannot happen, but the formatting must not print "-1 minutes" if it ever does.
        assertEquals(AndroidStrings.format("stats_duration_minutes", 0L), formatStatsDuration(-5_000L))
        assertTrue(formatStatsDuration(0L).isNotBlank())
    }
}
