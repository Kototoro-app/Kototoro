package org.skepsun.kototoro.details.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailsSourceSummaryRulesTest {

    @Test
    fun `the same plain source on both sides is shown once`() {
        assertTrue(sourceSummaryIsMerged("AGE", false, "AGE", false))
    }

    @Test
    fun `different sources keep both names`() {
        assertFalse(sourceSummaryIsMerged("AGE", false, "MANGADEX", false))
    }

    @Test
    fun `a tracker on either side keeps both names`() {
        assertFalse(sourceSummaryIsMerged("AGE", true, "AGE", false))
        assertFalse(sourceSummaryIsMerged("AGE", false, "AGE", true))
    }

    @Test
    fun `a missing side is never merged`() {
        assertFalse(sourceSummaryIsMerged(null, false, null, false))
        assertFalse(sourceSummaryIsMerged("AGE", false, null, false))
    }
}
