package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedPagingSnapshotPolicyTest {

    @Test
    fun `release retained snapshot when refreshed anchor was removed`() {
        assertFalse(
            shouldUseRetainedPagingSnapshot(
                retentionEnabled = true,
                hasPagingItems = true,
                hasRetainedSnapshot = true,
                returnTransitionSettled = true,
                retainedAnchorPrefixIsReady = false,
                pagingRefreshSettled = true,
                retainedAnchorIsLoaded = false,
            ),
        )
    }

    @Test
    fun `keep retained snapshot until refresh confirms missing anchor`() {
        assertTrue(
            shouldUseRetainedPagingSnapshot(
                retentionEnabled = true,
                hasPagingItems = true,
                hasRetainedSnapshot = true,
                returnTransitionSettled = true,
                retainedAnchorPrefixIsReady = false,
                pagingRefreshSettled = false,
                retainedAnchorIsLoaded = false,
            ),
        )
    }

    @Test
    fun `keep retained snapshot throughout return transition`() {
        assertTrue(
            shouldUseRetainedPagingSnapshot(
                retentionEnabled = true,
                hasPagingItems = true,
                hasRetainedSnapshot = true,
                returnTransitionSettled = false,
                retainedAnchorPrefixIsReady = true,
                pagingRefreshSettled = true,
                retainedAnchorIsLoaded = true,
            ),
        )
    }

    @Test
    fun `keep normal favourite snapshot while its live prefix is still loading`() {
        assertTrue(
            shouldUseRetainedPagingSnapshot(
                retentionEnabled = true,
                hasPagingItems = true,
                hasRetainedSnapshot = true,
                returnTransitionSettled = true,
                retainedAnchorPrefixIsReady = false,
                pagingRefreshSettled = true,
                retainedAnchorIsLoaded = true,
            ),
        )
    }
}
