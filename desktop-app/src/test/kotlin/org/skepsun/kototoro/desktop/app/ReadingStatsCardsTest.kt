package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.stats.StatsActivityCard
import org.skepsun.kototoro.core.ui.stats.StatsKindBreakdownCard
import org.skepsun.kototoro.core.ui.stats.StatsKindShare
import org.skepsun.kototoro.core.ui.stats.StatsSummaryCard
import org.skepsun.kototoro.core.ui.stats.StatsTimeDistributionCard
import org.skepsun.kototoro.core.ui.stats.statsDurationText

/**
 * The cards the statistics dashboard is now built from on both hosts: Android's summary plate, the activity card with
 * its streak pill, the eight three-hour buckets of the hour split, and the kind breakdown with each kind's share. The
 * desktop probe drives the real page through these same tags.
 */
@OptIn(ExperimentalTestApi::class)
class ReadingStatsCardsTest {
    @Test
    fun `the summary plate prints Android's figures under the eyebrow the host supplies`() {
        runDesktopComposeUiTest(width = 520, height = 400) {
            setContent {
                DesktopTheme {
                    StatsSummaryCard(
                        eyebrow = "周 · 全部",
                        totalDuration = 72 * 60_000L,
                        activeDays = 3,
                        sessions = 5,
                        works = 2,
                    )
                }
            }
            onNodeWithText("周 · 全部").assertExists()
            onNodeWithText(AndroidStrings.format("stats_duration_hours_minutes", 1L, 12L)).assertExists()
            onNodeWithText(AndroidStrings["stats_total_time"]).assertExists()
            onNodeWithTag("stats-total").assertExists()
            onNodeWithTag("stats-active-days").assertExists()
            onNodeWithTag("stats-sessions").assertExists()
            onNodeWithTag("stats-works").assertExists()
            onNodeWithText("3").assertExists()
        }
    }

    @Test
    fun `the activity card carries Android's title day count and streak pill around the host's plot`() {
        runDesktopComposeUiTest(width = 520, height = 400) {
            setContent {
                DesktopTheme {
                    StatsActivityCard(dayCount = 7, streak = 4) {
                        androidx.compose.material3.Text("plot")
                    }
                }
            }
            onNodeWithText(AndroidStrings["stats_activity"]).assertExists()
            onNodeWithText(AndroidStrings.format("stats_activity_days", 7)).assertExists()
            onNodeWithText(AndroidStrings.format("stats_streak", 4)).assertExists()
            onNodeWithTag("stats-activity-days").assertExists()
            onNodeWithTag("stats-activity-streak").assertExists()
            onNodeWithText("plot").assertExists()
        }
    }

    @Test
    fun `the hour split groups the day into eight three-hour buckets and names the busiest one`() {
        // Reading from 06:00 to 08:59 is the third bucket, so the pill names 06:00–09:00 and the bars keep Android's
        // index-based tags.
        val hourly = List(24) { hour -> if (hour in 6..8) 30L else 0L }
        runDesktopComposeUiTest(width = 620, height = 420) {
            setContent {
                DesktopTheme {
                    StatsTimeDistributionCard(hourlyActivity = hourly, periodLabel = "周")
                }
            }
            onNodeWithText(AndroidStrings["stats_time_distribution"]).assertExists()
            onNodeWithText(AndroidStrings.format("stats_time_distribution_subtitle", "周")).assertExists()
            onNodeWithText(
                AndroidStrings.format(
                    "stats_peak_period",
                    AndroidStrings.format("stats_hour_range", 6, 9),
                ),
            ).assertExists()
            (0 until 24 step 3).forEach { hour -> onNodeWithTag("stats-hour:$hour").assertExists() }
            onNodeWithTag("stats-hour:6").assertExists()
        }
    }

    @Test
    fun `the kind breakdown names each kind with its share of the reading time`() {
        runDesktopComposeUiTest(width = 620, height = 420) {
            setContent {
                DesktopTheme {
                    StatsKindBreakdownCard(
                        kinds = listOf(
                            StatsKindShare("MANGA", "漫画", Color(0xFF1565C0), 90 * 60_000L),
                            StatsKindShare("NOVEL", "小说", Color(0xFF6A1B9A), 30 * 60_000L),
                        ),
                    )
                }
            }
            onNodeWithText(AndroidStrings["stats_content_breakdown"]).assertExists()
            onNodeWithTag("stats-kind:MANGA").assertExists()
            onNodeWithTag("stats-kind:NOVEL").assertExists()
            onNodeWithText("漫画").assertExists()
            onNodeWithText("小说").assertExists()
            // 90 of 120 minutes is three quarters; Android prints the same "duration · percent" pair.
            onNodeWithText(
                "${AndroidStrings.format("stats_duration_hours_minutes", 1L, 30L)} · 75%",
            ).assertExists()
        }
    }

    @Test
    fun `a duration is rounded the way Android rounds it`() {
        assertEquals(AndroidStrings.format("stats_duration_minutes", 0L), statsDurationText(0L, DesktopUiStrings))
        assertEquals(
            AndroidStrings.format("stats_duration_hours", 2L),
            statsDurationText(120 * 60_000L, DesktopUiStrings),
        )
        assertEquals(
            AndroidStrings.format("stats_duration_hours_minutes", 2L, 5L),
            statsDurationText(125 * 60_000L, DesktopUiStrings),
        )
    }
}
