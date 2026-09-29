package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.migration.domain.SourceHealth
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.ui.sources.MigrationSourcesState

class MigrationSourcesStateTest {

    private fun health(name: String, status: SourceHealthStatus, vararg ids: Long) =
        SourceHealth(ContentSource(name), status, null, ids.toList())

    private val state = MigrationSourcesState(
        sources = listOf(
            health("GONE", SourceHealthStatus.UNINSTALLED, 1, 2),
            health("OFF", SourceHealthStatus.DISABLED, 3),
            health("FINE", SourceHealthStatus.HEALTHY, 4),
        ),
        isLoading = false,
    )

    @Test
    fun `disabled sources get their own group and stay out of bulk migration`() {
        assertEquals(listOf("GONE"), state.attention.map { it.source.name })
        assertEquals(listOf("OFF"), state.disabled.map { it.source.name })
        assertEquals(listOf("FINE"), state.healthy.map { it.source.name })
        assertArrayEquals(longArrayOf(1, 2), state.attentionIds)
    }
}
