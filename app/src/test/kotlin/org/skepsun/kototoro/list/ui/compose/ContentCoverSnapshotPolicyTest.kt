package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentCoverSnapshotPolicyTest {

    @Test
    fun `cover snapshot is retained only while shared transition is enabled`() {
        assertTrue(shouldRetainContentCoverSnapshot(sharedTransitionEnabled = true))
        assertFalse(shouldRetainContentCoverSnapshot(sharedTransitionEnabled = false))
    }
}
