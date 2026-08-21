package org.skepsun.kototoro.core.jsonsource

import android.content.Context
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSourceInfo

/**
 * Data class representing information about a source group.
 * 
 * This class contains:
 * - The group type (content or origin)
 * - A display name for the group
 * - The list of sources in this group
 * - The count of sources
 * - Whether the group is currently collapsed in the UI
 * 
 * @property group The source group (Content or Origin)
 * @property name The display name for this group
 * @property sources The list of sources in this group
 * @property count The number of sources in this group
 * @property isCollapsed Whether this group is collapsed in the UI
 */
data class SourceGroupInfo(
    val group: SourceGroup,
    val name: String,
    val sources: List<ContentSourceInfo>,
    val count: Int = sources.size,
    val isCollapsed: Boolean = false,
) {
    /**
     * Creates a copy of this group with the collapsed state toggled.
     */
    fun toggleCollapsed(): SourceGroupInfo {
        return copy(isCollapsed = !isCollapsed)
    }
    
    /**
     * Creates a copy of this group with a new collapsed state.
     */
    fun withCollapsed(collapsed: Boolean): SourceGroupInfo {
        return copy(isCollapsed = collapsed)
    }
    
    /**
     * Gets a display label for the group type.
     */
    fun getGroupTypeLabel(context: Context): String {
        return when (group) {
            is SourceGroup.Content -> when (group.type) {
                ContentGroup.MANGA -> context.getString(R.string.source_group_manga)
                ContentGroup.NOVEL -> context.getString(R.string.source_group_novel)
                ContentGroup.VIDEO -> context.getString(R.string.source_group_video)
                ContentGroup.HENTAI_MANGA -> context.getString(R.string.source_group_hentai_manga)
                ContentGroup.HENTAI_NOVEL -> context.getString(R.string.source_group_hentai_novel)
                ContentGroup.HENTAI_VIDEO -> context.getString(R.string.source_group_hentai_video)
                ContentGroup.OTHER -> context.getString(R.string.source_group_other)
            }
            is SourceGroup.Origin -> when (group.type) {
                OriginGroup.NATIVE -> context.getString(R.string.source_group_native)
                OriginGroup.LEGADO_JSON -> context.getString(R.string.source_group_legado)
                OriginGroup.TVBOX_JSON -> context.getString(R.string.source_group_tvbox)
                OriginGroup.JS_JSON -> context.getString(R.string.source_group_javascript)
                OriginGroup.EXTERNAL -> context.getString(R.string.source_group_external)
                OriginGroup.MIHON -> context.getString(R.string.source_group_mihon)
                OriginGroup.ANIYOMI -> context.getString(R.string.source_group_aniyomi)
                OriginGroup.IREADER -> context.getString(R.string.source_type_ireader)
                OriginGroup.CLOUDSTREAM -> context.getString(R.string.source_type_cloudstream)
                OriginGroup.LNREADER_JSON -> context.getString(R.string.source_group_lnreader)
            }
        }
    }
}

/**
 * Data class representing a complete list of grouped sources.
 * 
 * This class organizes sources into multiple groups and provides
 * methods to manipulate the grouping structure.
 * 
 * @property groups The list of all source groups
 */
data class GroupedSourceList(
    val groups: List<SourceGroupInfo>,
) {
    /**
     * Gets the total count of all sources across all groups.
     */
    val totalCount: Int
        get() = groups.sumOf { it.count }
    
    /**
     * Toggles the collapsed state of a specific group.
     * 
     * @param groupIndex The index of the group to toggle
     * @return A new GroupedSourceList with the updated group
     */
    fun toggleGroup(groupIndex: Int): GroupedSourceList {
        if (groupIndex !in groups.indices) {
            return this
        }
        
        val updatedGroups = groups.toMutableList()
        updatedGroups[groupIndex] = updatedGroups[groupIndex].toggleCollapsed()
        
        return copy(groups = updatedGroups)
    }
    
    /**
     * Toggles the collapsed state of a group by its SourceGroup.
     * 
     * @param group The SourceGroup to toggle
     * @return A new GroupedSourceList with the updated group
     */
    fun toggleGroup(group: SourceGroup): GroupedSourceList {
        val index = groups.indexOfFirst { it.group == group }
        return if (index >= 0) {
            toggleGroup(index)
        } else {
            this
        }
    }
    
    /**
     * Collapses all groups.
     * 
     * @return A new GroupedSourceList with all groups collapsed
     */
    fun collapseAll(): GroupedSourceList {
        return copy(groups = groups.map { it.withCollapsed(true) })
    }
    
    /**
     * Expands all groups.
     * 
     * @return A new GroupedSourceList with all groups expanded
     */
    fun expandAll(): GroupedSourceList {
        return copy(groups = groups.map { it.withCollapsed(false) })
    }
    
    /**
     * Filters groups to only include those with sources.
     * 
     * @return A new GroupedSourceList with only non-empty groups
     */
    fun filterNonEmpty(): GroupedSourceList {
        return copy(groups = groups.filter { it.count > 0 })
    }
    
    /**
     * Gets all sources as a flat list (ignoring grouping).
     * 
     * @return List of all ContentSourceInfo across all groups
     */
    fun getAllSources(): List<ContentSourceInfo> {
        return groups.flatMap { it.sources }
    }
    
    companion object {
        /**
         * Creates an empty GroupedSourceList.
         */
        fun empty(): GroupedSourceList {
            return GroupedSourceList(emptyList())
        }
        
        /**
         * Creates a GroupedSourceList from a list of sources by grouping them.
         * 
         * @param sources The list of sources to group
         * @param groupBy The grouping strategy (content or origin)
         * @param sourceGroupManager The manager to use for grouping
         * @return A new GroupedSourceList with sources organized into groups
         */
        fun fromSources(
            sources: List<ContentSourceInfo>,
            groupBy: GroupingStrategy,
            sourceGroupManager: SourceGroupManager,
        ): GroupedSourceList {
            val groups = when (groupBy) {
                GroupingStrategy.BY_CONTENT -> {
                    ContentGroup.entries.map { contentGroup ->
                        val groupSources = sources.filter { sourceInfo ->
                            sourceGroupManager.getContentGroup(sourceInfo.mangaSource) == contentGroup
                        }
                        SourceGroupInfo(
                            group = SourceGroup.Content(contentGroup),
                            name = contentGroup.name,
                            sources = groupSources,
                        )
                    }
                }
                GroupingStrategy.BY_ORIGIN -> {
                    OriginGroup.entries.map { originGroup ->
                        val groupSources = sources.filter { sourceInfo ->
                            sourceGroupManager.getOriginGroup(sourceInfo.mangaSource) == originGroup
                        }
                        SourceGroupInfo(
                            group = SourceGroup.Origin(originGroup),
                            name = originGroup.name,
                            sources = groupSources,
                        )
                    }
                }
            }
            
            return GroupedSourceList(groups)
        }
    }
}

/**
 * Enum representing different strategies for grouping sources.
 */
enum class GroupingStrategy {
    /**
     * Group sources by content type (manga, novel, video)
     */
    BY_CONTENT,
    
    /**
     * Group sources by origin type (native, JSON Legado, JSON TVBox)
     */
    BY_ORIGIN,
}
