package org.skepsun.kototoro.download.ui.worker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ActiveDownloadRegistryTest {

    @Test
    fun `prioritized work takes the next slot without blocking work that already holds it`() {
        val active = UUID.randomUUID()
        val waiting = UUID.randomUUID()
        val prioritized = UUID.randomUUID()
        try {
            ActiveDownloadRegistry.register(active, isPaused = false)
            ActiveDownloadRegistry.register(waiting, isPaused = false)
            ActiveDownloadRegistry.register(prioritized, isPaused = false)

            assertTrue(ActiveDownloadRegistry.isTurn(active, limit = 1))
            ActiveDownloadRegistry.setPrioritized(prioritized, prioritized = true)
            assertTrue(ActiveDownloadRegistry.isTurn(active, limit = 1))
            assertFalse(ActiveDownloadRegistry.isTurn(prioritized, limit = 1))
            ActiveDownloadRegistry.unregister(active)
            assertFalse(ActiveDownloadRegistry.isTurn(waiting, limit = 1))
            assertTrue(ActiveDownloadRegistry.isTurn(prioritized, limit = 1))
        } finally {
            ActiveDownloadRegistry.unregister(active)
            ActiveDownloadRegistry.unregister(waiting)
            ActiveDownloadRegistry.unregister(prioritized)
        }
    }
}
