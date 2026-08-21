package org.skepsun.kototoro.main.ui

import android.view.View
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag

/**
 * Callback contract that bridges list/favorites filter state to the global
 * Compose TopBar. Compose screens implement this directly and register it on
 * [MainActivity] via [MainActivity.setActiveFilterCallback].
 */
interface SearchBarFilterCallback {
    fun onContentTypeSelected(tab: BrowseGroupTab)
    fun onSourceTagSelected(tag: SourceTag?)
    fun getSelectedContentType(): BrowseGroupTab
    fun getSelectedSourceTags(): Set<SourceTag>
    fun applyContentTypeSelection(tab: BrowseGroupTab) {
        if (getSelectedContentType() != tab) {
            onContentTypeSelected(tab)
        }
    }
    fun applySourceTagSelection(tags: Set<SourceTag>) {
        val current = getSelectedSourceTags()
        if (current == tags) {
            return
        }
        if (tags.isEmpty()) {
            onSourceTagSelected(null)
            return
        }
        (current - tags).forEach(::onSourceTagSelected)
        (tags - current).forEach(::onSourceTagSelected)
    }
    fun getSourceTagEntries(): List<SourceTag> = SourceTag.quickFilterEntries
    fun isContentTypeFilterVisible(): Boolean = true
    fun isSourceTagFilterVisible(): Boolean = true
    fun isLanguagePresetFilterVisible(): Boolean = true
    fun isContentTypeEnabled(tab: BrowseGroupTab): Boolean = true
    fun isSourceTagEnabled(tag: SourceTag): Boolean = true
    fun getSourceTagIconRes(): Int = 0
    /** Return true to consume the click and prevent default popup */
    fun onFilterIconClicked(anchor: View): Boolean = false
}
