package org.skepsun.kototoro.explore.ui.compose


import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

internal fun Set<Long>.toggle(id: Long): Set<Long> {
    return if (id in this) this - id else this + id
}

@Composable
private fun sourceTypeAccent(contentType: ContentType): Color = when (contentType) {
    ContentType.VIDEO -> MaterialTheme.colorScheme.tertiary
    ContentType.NOVEL -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}


internal fun List<ContentSourceItem>.toQuickAccessGroups(
    isGroupedByLanguage: Boolean,
    context: android.content.Context,
): List<SourceQuickAccessGroup> {
    if (isEmpty()) {
        return emptyList()
    }
    if (!isGroupedByLanguage) {
        return listOf(SourceQuickAccessGroup(title = null, sources = this))
    }
    val result = ArrayList<SourceQuickAccessGroup>()
    val (pinned, unpinned) = partition { it.source.isPinned }
    if (pinned.isNotEmpty()) {
        result += SourceQuickAccessGroup(
            title = context.getString(R.string.source_pinned),
            sources = pinned,
        )
    }
    val grouped = unpinned
        .groupBy { sourceItem ->
            sourceItem.source.mangaSource.getLocale()
                ?.getDisplayName(Locale.getDefault())
                ?.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
                ?: context.getString(R.string.other)
        }
        .toSortedMap()
    grouped.forEach { (language, sourcesInLanguage) ->
        if (sourcesInLanguage.isNotEmpty()) {
            result += SourceQuickAccessGroup(
                title = language,
                sources = sourcesInLanguage,
            )
        }
    }
    return result
}

internal fun List<SourceQuickAccessGroup>.takeVisibleSourceGroups(
    maxSources: Int,
): List<SourceQuickAccessGroup> {
    if (maxSources == Int.MAX_VALUE) {
        return this
    }
    var remaining = maxSources
    val result = ArrayList<SourceQuickAccessGroup>(size)
    for (group in this) {
        if (remaining <= 0) break
        val visibleSources = group.sources.take(remaining)
        if (visibleSources.isNotEmpty()) {
            result += group.copy(sources = visibleSources)
            remaining -= visibleSources.size
        }
    }
    return result
}

