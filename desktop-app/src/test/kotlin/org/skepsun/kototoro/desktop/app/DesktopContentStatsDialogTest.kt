package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.library.LibraryContentStats
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.stats.domain.ReadingDayStats
import org.skepsun.kototoro.stats.domain.StatsContentKind

/**
 * Android's per-work statistics sheet: picking a work in the statistics list opens its title, its ninety-day chart, the
 * three metric plates and a "details" button, and the sheet is what navigates. The sheet renders from a snapshot alone,
 * so these tests pin its numbers without a database.
 */
class DesktopContentStatsDialogTest {

    private val source = SourceRef("MIHON_1", "zh", "MANGA")

    private fun content(id: Long, title: String) = SourceContent(id, title, emptySet(), "/work/$id",
        "https://fixture.invalid/work/$id", .5f, null, null, emptySet(), "ONGOING", emptySet(), source)

    /** Android's snapshot: ninety days of seconds, with the reading days spread out like a real history. */
    private fun days(vararg durations: Long) = (0 until 90).map { index ->
        ReadingDayStats("2025-11-${(index % 28 + 1).toString().padStart(2, '0')}", index * 86_400_000L,
            durations.getOrNull(index) ?: 0L)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the sheet repeats the work's numbers and its buttons close or open the details`() = runDesktopComposeUiTest {
        val work = content(1, "统计作品")
        var dismissed = 0
        val opened = mutableListOf<Long>()
        setContent {
            DesktopTheme {
                DesktopContentStatsDialog(
                    sheet = DesktopContentStats(work, LibraryContentStats(
                        kind = StatsContentKind.MANGA,
                        totalDuration = 3_720_000,
                        sessionCount = 3,
                        units = 41,
                        firstActivityAt = 1_700_000_000_000L,
                        dailyActivity = days(0, 600_000, 0, 3_120_000),
                    )),
                    onDismiss = { dismissed++ },
                    onOpenDetails = { opened += it.id },
                )
            }
        }
        onNodeWithText("统计作品").assertIsDisplayed()
        onNodeWithTag("content-stats-chart").assertIsDisplayed()
        // The bare "first read" line Android prints under the chart, and the three plates under it.
        onNodeWithTag("content-stats-first").assertIsDisplayed()
        onNodeWithText(formatStatsDuration(3_720_000)).assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
        onNodeWithText("41").assertIsDisplayed()
        onNodeWithText(AndroidStrings["stats_total_time"]).assertIsDisplayed()
        onNodeWithText(AndroidStrings["stats_sessions"]).assertIsDisplayed()
        onNodeWithText(AndroidStrings["stats_unit_pages"]).assertIsDisplayed()
        onNodeWithTag("content-stats-close").performClick()
        assertEquals(1, dismissed)
        onNodeWithTag("content-stats-details").performClick()
        assertEquals(listOf(1L), opened)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a work without reading time keeps the sheet and shows Android's empty chart`() = runDesktopComposeUiTest {
        setContent {
            DesktopTheme {
                DesktopContentStatsDialog(
                    sheet = DesktopContentStats(content(2, "小说作品"), LibraryContentStats(
                        kind = StatsContentKind.NOVEL, dailyActivity = days(),
                    )),
                    onDismiss = {},
                    onOpenDetails = {},
                )
            }
        }
        onNodeWithTag("content-stats-chart").assertIsDisplayed()
        onNodeWithText(AndroidStrings["empty_stats_text"]).assertIsDisplayed()
        // Nothing was ever read, so Android has no first-activity line to draw.
        onNodeWithTag("content-stats-first").assertDoesNotExist()
        // The unit plate follows the work's content kind, not manga pages.
        onNodeWithText(AndroidStrings["stats_unit_chapters"]).assertIsDisplayed()
        assertTrue(days().all { it.duration == 0L })
    }
}
