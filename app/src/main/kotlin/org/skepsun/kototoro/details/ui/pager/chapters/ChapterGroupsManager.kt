package org.skepsun.kototoro.details.ui.pager.chapters

import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.list.ui.model.CollapsibleListHeader
import org.skepsun.kototoro.list.ui.model.ListModel

/**
 * Manages the collapsed/expanded state of chapter groups
 */
class ChapterGroupsManager {

    private val collapsedGroups = mutableSetOf<String>()

    /**
     * Toggles the collapsed state of a group
     * @param groupId The group identifier
     * @return true if the group is now expanded, false if collapsed
     */
    fun toggleGroup(groupId: String): Boolean {
        return if (collapsedGroups.contains(groupId)) {
            collapsedGroups.remove(groupId)
            true // Now expanded
        } else {
            collapsedGroups.add(groupId)
            false // Now collapsed
        }
    }

    /**
     * Checks if a group is expanded
     * @param groupId The group identifier
     * @return true if expanded, false if collapsed
     */
    fun isGroupExpanded(groupId: String): Boolean {
        return !collapsedGroups.contains(groupId)
    }

    /**
     * Applies the collapsed state to a list of items with collapsible headers
     * @param items The list of items including headers and chapters
     * @return A new list with collapsed groups hidden
     */
    fun applyCollapsedState(items: List<ListModel>): List<ListModel> {
        val result = mutableListOf<ListModel>()
        var currentHeader: CollapsibleListHeader? = null
        var isCurrentGroupCollapsed = false

        for (item in items) {
            when (item) {
                is CollapsibleListHeader -> {
                    currentHeader = item
                    isCurrentGroupCollapsed = !isGroupExpanded(item.groupId)
                    // Update the header's expanded state
                    val updatedHeader = item.copy(isExpanded = !isCurrentGroupCollapsed)
                    result.add(updatedHeader)
                }
                is ChapterListItem -> {
                    // Only add chapter if its group is not collapsed
                    if (!isCurrentGroupCollapsed) {
                        result.add(item)
                    }
                }
                else -> {
                    // Add other items (like regular headers) as-is
                    result.add(item)
                    currentHeader = null
                    isCurrentGroupCollapsed = false
                }
            }
        }

        return result
    }

    /**
     * Clears all collapsed state
     */
    fun clear() {
        collapsedGroups.clear()
    }
}
