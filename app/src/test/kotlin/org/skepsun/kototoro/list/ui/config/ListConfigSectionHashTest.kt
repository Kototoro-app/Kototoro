package org.skepsun.kototoro.list.ui.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListConfigSectionHashTest {

    @Test
    fun `home section data objects must produce distinct view model keys`() {
        val keys = listOf(
            ListConfigSection.Home,
            ListConfigSection.HomeHistory,
            ListConfigSection.HomeUpdates,
            ListConfigSection.HomeRecommendations,
        ).map { "list-config-${it.hashCode()}" }

        assertTrue(keys.toSet().size == keys.size, "VM keys collide: $keys")
    }
}
