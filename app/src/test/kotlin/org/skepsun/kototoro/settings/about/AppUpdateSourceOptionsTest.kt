package org.skepsun.kototoro.settings.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.github.AppUpdateSource
import org.skepsun.kototoro.core.github.AppUpdateSourceProbe

class AppUpdateSourceOptionsTest {

    @Test
    fun `active update sources exclude disabled GitCode route`() {
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

        assertEquals(listOf(AppUpdateSource.GITHUB), options.map { it.source })
        assertEquals(listOf(probes[AppUpdateSource.GITHUB]), options.map { it.probe })
    }
}
