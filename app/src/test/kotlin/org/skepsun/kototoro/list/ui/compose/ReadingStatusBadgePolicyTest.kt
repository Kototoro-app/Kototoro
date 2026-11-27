package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.CardProgressStyle
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.domain.deriveReadingStatus
import org.skepsun.kototoro.list.domain.toReadingStatusOrNull
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * The reading-status corner badge (#750): the pure decision that says which corner draws it,
 * covering every status, both progress styles and the bottom-end corner the circular progress
 * badge already owns.
 */
class ReadingStatusBadgePolicyTest {

    private val withBadge = setOf("reading_status")

    private fun decide(
        status: ScrobblingStatus?,
        style: CardProgressStyle = CardProgressStyle.BOTTOM_BAR,
        corner: ContentCardCorner = ContentCardCorner.TOP_START,
        badges: Set<String> = withBadge,
    ) = shouldDrawReadingStatusBadge(status, style, corner, badges)

    @Test
    fun `badge is off when the user did not select it in the corner`() {
        // Default configuration: the badge key is absent, so nothing changes for existing users.
        assertFalse(decide(ScrobblingStatus.READING, badges = setOf("tracker", "score")))
        assertFalse(decide(ScrobblingStatus.READING, badges = emptySet()))
    }

    @Test
    fun `planned and absent statuses draw nothing`() {
        assertFalse(decide(null))
        assertFalse(decide(ScrobblingStatus.PLANNED))
        assertFalse(decide(ScrobblingStatus.PLANNED, style = CardProgressStyle.CIRCULAR_BADGE))
    }

    @Test
    fun `bottom bar style draws every status but planned in every corner`() {
        for (status in ScrobblingStatus.entries) {
            for (corner in ContentCardCorner.entries) {
                val expected = status != ScrobblingStatus.PLANNED
                assertEquals(
                    expected,
                    decide(status, CardProgressStyle.BOTTOM_BAR, corner),
                    "$status at $corner",
                )
            }
        }
    }

    @Test
    fun `circular style hides completed and the ring corner`() {
        for (status in ScrobblingStatus.entries) {
            for (corner in ContentCardCorner.entries) {
                val expected = status != ScrobblingStatus.PLANNED &&
                    status != ScrobblingStatus.COMPLETED &&
                    corner != ContentCardCorner.BOTTOM_END
                assertEquals(
                    expected,
                    decide(status, CardProgressStyle.CIRCULAR_BADGE, corner),
                    "$status at $corner",
                )
            }
        }
    }

    @Test
    fun `circular style never draws in the ring corner`() {
        for (status in ScrobblingStatus.entries) {
            assertFalse(
                decide(status, CardProgressStyle.CIRCULAR_BADGE, ContentCardCorner.BOTTOM_END),
                "$status in the bottom-end corner",
            )
        }
    }

    @Test
    fun `bottom bar style draws the status in the ring corner`() {
        for (status in ScrobblingStatus.entries) {
            assertEquals(
                status != ScrobblingStatus.PLANNED,
                decide(status, CardProgressStyle.BOTTOM_BAR, ContentCardCorner.BOTTOM_END),
                "$status in the bottom-end corner",
            )
        }
    }

    @Test
    fun `derive prefers the persisted status over the progress`() {
        assertEquals(ScrobblingStatus.ON_HOLD, deriveReadingStatus(ScrobblingStatus.ON_HOLD, progress(1f)))
        assertEquals(ScrobblingStatus.DROPPED, deriveReadingStatus(ScrobblingStatus.DROPPED, null))
    }

    @Test
    fun `derive falls back to the progress when nothing is persisted`() {
        assertNull(deriveReadingStatus(null, null))
        assertNull(deriveReadingStatus(null, progress(0f)))
        assertEquals(ScrobblingStatus.READING, deriveReadingStatus(null, progress(0.4f)))
        assertEquals(ScrobblingStatus.COMPLETED, deriveReadingStatus(null, progress(1f)))
    }

    @Test
    fun `persisted status names parse leniently`() {
        assertEquals(ScrobblingStatus.RE_READING, "RE_READING".toReadingStatusOrNull())
        assertNull("not_a_status".toReadingStatusOrNull())
        assertNull("null".let { null }.toReadingStatusOrNull())
    }

    @Test
    fun `badge key matches the value offered by the settings arrays`() {
        assertTrue("reading_status" in withBadge)
    }

    private fun progress(percent: Float) = ReadingProgress(
        percent = percent,
        totalChapters = 40,
        mode = ProgressIndicatorMode.PERCENT_READ,
    )
}
