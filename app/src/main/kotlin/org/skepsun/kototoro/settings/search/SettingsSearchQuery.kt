package org.skepsun.kototoro.settings.search

/**
 * Filters the settings index for a search query.
 *
 * Renaming a preference must not strand the people who search for its old wording, so [SettingsItem.aliases] are
 * searched too. Title matches come first: an alias hit only fills in results the visible names did not already cover.
 */
fun List<SettingsItem>.searchSettings(query: String): List<SettingsItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val titleHits = filter { it.title.contains(trimmed, ignoreCase = true) }
    val aliasHits = filter { item ->
        item.aliases.any { it.contains(trimmed, ignoreCase = true) }
    }
    return titleHits + aliasHits.filterNot { aliasHit -> titleHits.any { it.key == aliasHit.key } }
}
