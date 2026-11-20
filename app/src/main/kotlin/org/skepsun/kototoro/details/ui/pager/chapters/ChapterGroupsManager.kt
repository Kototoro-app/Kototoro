package org.skepsun.kototoro.details.ui.pager.chapters

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.skepsun.kototoro.core.ui.chapters.allChapterGroupsCollapsed
import org.skepsun.kototoro.core.ui.chapters.collapseChapterGroups
import org.skepsun.kototoro.core.ui.chapters.toggledChapterGroups
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.list.ui.model.CollapsibleListHeader
import org.skepsun.kototoro.list.ui.model.ListModel

/**
 * Manages the collapsed/expanded state of chapter groups; the state is snapshot-backed, so a composition that applies
 * it recomposes when a group is toggled.
 */
@Stable
class ChapterGroupsManager {

    var collapsedGroups by mutableStateOf(emptySet<String>())
        private set

    /**
     * Toggles the collapsed state of a group
     * @param groupId The group identifier
     * @return true if the group is now expanded, false if collapsed
     */
    fun toggleGroup(groupId: String): Boolean {
        val expanded = groupId in collapsedGroups
        collapsedGroups = if (expanded) collapsedGroups - groupId else collapsedGroups + groupId
        return expanded
    }

    /**
     * Checks if a group is expanded
     * @param groupId The group identifier
     * @return true if expanded, false if collapsed
     */
    fun isGroupExpanded(groupId: String): Boolean {
        return groupId !in collapsedGroups
    }

    /** The foldable groups of [items], in list order. */
    fun foldableGroupIds(items: List<ListModel>): List<String> = items
        .filterIsInstance<CollapsibleListHeader>()
        .filter { it.isCollapsible }
        .map { it.groupId }
        .distinct()

    /** Null hides the "expand/collapse all" action; true when every group in [groupIds] is collapsed. */
    fun areAllCollapsed(groupIds: List<String>): Boolean? = allChapterGroupsCollapsed(groupIds, collapsedGroups)

    /** Expands every group in [groupIds] when all are collapsed, otherwise collapses them all. */
    fun toggleAll(groupIds: List<String>) {
        collapsedGroups = toggledChapterGroups(groupIds, collapsedGroups)
    }

    /**
     * Applies the collapsed state to a list of items with collapsible headers
     * @param items The list of items including headers and chapters
     * @return A new list with collapsed groups hidden
     */
    fun applyCollapsedState(items: List<ListModel>): List<ListModel> = collapseChapterGroups(
        items = items,
        groupKey = { (it as? CollapsibleListHeader)?.takeIf { header -> header.isCollapsible }?.groupId },
        // Other rows (like regular headers) end a folded group, as before.
        isHeader = { it !is ChapterListItem },
        collapsed = collapsedGroups,
    ).map { item ->
        // Update the header's expanded state
        if (item is CollapsibleListHeader) item.copy(isExpanded = isGroupExpanded(item.groupId)) else item
    }

    /**
     * Clears all collapsed state
     */
    fun clear() {
        collapsedGroups = emptySet()
    }
}
