package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The details page used to explain one cause twice: an inline "this source may be broken (Not
 * installed)" state plus a floating "this source is not supported" snackbar that covered the "Read"
 * dock. These rules keep exactly one explanation, and keep other failures visible.
 */
class DetailsSourceHealthRulesTest {

    @Test
    fun `recorded health verdict wins over the fallback`() {
        assertEquals(
            DetailsSourceNoticeKind.BROKEN,
            detailsSourceNoticeKind(DetailsSourceNoticeKind.BROKEN, sourceUnavailable = true),
        )
    }

    @Test
    fun `a failed load explains itself when health was never queried`() {
        assertEquals(
            DetailsSourceNoticeKind.UNINSTALLED,
            detailsSourceNoticeKind(DetailsSourceNoticeKind.NONE, sourceUnavailable = true),
        )
    }

    @Test
    fun `a working source has nothing to announce`() {
        assertEquals(
            DetailsSourceNoticeKind.NONE,
            detailsSourceNoticeKind(DetailsSourceNoticeKind.NONE, sourceUnavailable = false),
        )
    }

    @Test
    fun `a source that cannot serve content is page state, not an error report`() {
        // The page renders it as an inline notice (health verdict or the "not installed" fallback),
        // so a snackbar for the same cause would only duplicate it over the "Read" dock.
        assertTrue(detailsOwnsSourceError(isUnsupportedSourceError = true))
    }

    @Test
    fun `other failures still reach the snackbar`() {
        assertFalse(detailsOwnsSourceError(isUnsupportedSourceError = false))
    }
}
