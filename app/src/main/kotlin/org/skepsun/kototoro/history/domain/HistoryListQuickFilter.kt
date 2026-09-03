package org.skepsun.kototoro.history.domain

import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.AppSettings
import kotlinx.coroutines.CompletableDeferred
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.history.domain.library.HistoryCardEntry
import org.skepsun.kototoro.history.domain.library.HistorySnapshot
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import javax.inject.Inject

class HistoryListQuickFilter @Inject constructor(
    private val settings: AppSettings,
    networkState: NetworkState,
) : ContentListQuickFilter(settings) {

    private val snapshotOptions = CompletableDeferred<List<ListFilterOption>>()

    init {
        setFilterOption(ListFilterOption.Downloaded, !networkState.value)
    }

    /** Supplies the first already-loaded history snapshot to the lazy chip builder. */
    internal fun acceptSnapshot(snapshot: HistorySnapshot) {
        snapshotOptions.complete(buildHistorySnapshotFilterOptions(snapshot.rows))
    }

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
        add(ListFilterOption.Downloaded)
        if (settings.isTrackerEnabled) {
            add(ListFilterOption.Macro.NEW_CHAPTERS)
        }
        add(ListFilterOption.Macro.COMPLETED)
        add(ListFilterOption.Macro.FAVORITE)
        add(ListFilterOption.NOT_FAVORITE)
        if (!settings.isHistoryExcludeNsfw) {
            add(ListFilterOption.Macro.NSFW)
        }
        addAll(snapshotOptions.await())
    }

}

internal fun buildHistorySnapshotFilterOptions(rows: List<HistoryCardEntry>): List<ListFilterOption> {
    val sourcesByName = HashMap<String, org.skepsun.kototoro.parsers.model.ContentSource>()
    val sourceCounts = LinkedHashMap<String, Int>()
    val tagCounts = LinkedHashMap<ContentTag, Int>()
    for (row in rows) {
        if (row.displayMangaId == null || row.sourceName.isBlank()) {
            continue
        }
        val source = sourcesByName.getOrPut(row.sourceName) { ContentSource(row.sourceName) }
        sourceCounts[row.sourceName] = sourceCounts.getOrDefault(row.sourceName, 0) + 1
        for (tag in row.tags) {
            val contentTag = ContentTag(title = tag.title, key = tag.key, source = source)
            tagCounts[contentTag] = tagCounts.getOrDefault(contentTag, 0) + 1
        }
    }
    return buildList(tagCounts.size + sourceCounts.size) {
        tagCounts.entries
            .sortedByDescending { it.value }
            .mapTo(this) { ListFilterOption.Tag(it.key) }
        sourceCounts.entries
            .sortedByDescending { it.value }
            .mapTo(this) { (sourceName, _) -> ListFilterOption.Source(sourcesByName.getValue(sourceName)) }
    }
}
