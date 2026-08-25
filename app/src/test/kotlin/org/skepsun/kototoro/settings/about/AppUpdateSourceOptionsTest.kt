package org.skepsun.kototoro.settings.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.github.AppUpdateSource
import org.skepsun.kototoro.core.github.AppUpdateSourceProbe

class AppUpdateSourceOptionsTest {

    @Test
    fun `all update sources remain visible with their probe results`() {
        val probes = mapOf(
            AppUpdateSource.GITHUB to AppUpdateSourceProbe(
                latencyMillis = 120L,
                isAvailable = true,
            ),
            AppUpdateSource.GITCODE to AppUpdateSourceProbe(
                latencyMillis = null,
                isAvailable = false,
            ),
        )

        val options = buildAppUpdateSourceOptions(probes)

        assertEquals(AppUpdateSource.entries, options.map { it.source })
        assertEquals(probes.values.toList(), options.map { it.probe })
    }
}
