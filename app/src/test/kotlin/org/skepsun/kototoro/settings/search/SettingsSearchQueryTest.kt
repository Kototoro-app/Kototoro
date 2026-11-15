package org.skepsun.kototoro.settings.search

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.SettingsDestination

class SettingsSearchQueryTest {
    private fun item(key: String, title: String, aliases: List<String> = emptyList()) = SettingsItem(
        key = key,
        title = title,
        breadcrumbs = emptyList(),
        destination = SettingsDestination.Root,
        aliases = aliases,
    )

    @Test
    fun `a renamed preference is still found by its old wording`() {
        val index = listOf(
            item("tracker_freq", "Frequency of check", aliases = listOf("Check for new chapters")),
            item("theme", "Color theme"),
        )

        index.searchSettings("check for new").map { it.key } shouldContainExactly listOf("tracker_freq")
    }

    @Test
    fun `rows matched by name come before rows matched only by alias`() {
        val index = listOf(
            item("freq", "Frequency of check", aliases = listOf("New chapter alerts")),
            item("alerts", "New chapter alerts"),
        )

        index.searchSettings("new chapter").map { it.key } shouldContainExactly listOf("alerts", "freq")
    }

    @Test
    fun `a preference matching both its name and an alias is listed once`() {
        val index = listOf(item("alerts", "New chapter alerts", aliases = listOf("New chapter alerts")))

        index.searchSettings("alerts").map { it.key } shouldContainExactly listOf("alerts")
    }

    @Test
    fun `blank queries match nothing instead of everything`() {
        val index = listOf(item("theme", "Color theme"))

        index.searchSettings("   ").shouldBeEmpty()
    }
}
