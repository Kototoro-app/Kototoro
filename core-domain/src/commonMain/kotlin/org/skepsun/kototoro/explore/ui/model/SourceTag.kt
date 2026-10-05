package org.skepsun.kototoro.explore.ui.model

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup

/**
 * Single-select source tags shown in the secondary filter bar.
 *
 * BUILTIN: filter native sources
 * Mihon : filter Mihon-origin sources
 * Aniyomi: filter Aniyomi-origin sources
 * JSON  : filter JSON-origin sources (Legado/TVBox/JS)
 */
enum class SourceTag(
    val id: String,
) {
    BUILTIN("builtin"),
    MIHON("mihon"),
    ANIYOMI("aniyomi"),
    LEGADO("legado"),
    TVBOX("tvbox"),
    IREADER("ireader"),
    CLOUDSTREAM("cloudstream"),
    LNREADER("lnreader"),
    TSUNDOKU("tsundoku"),
    PINNED("pinned");

    /**
     * Whether this tag matches the given content and origin group.
     */
    fun matches(contentGroup: ContentGroup, originGroup: OriginGroup): Boolean = matchesOrigin(originGroup)

    /** Every tag classifies by where a source comes from; hosts that only know the origin call this directly. */
    fun matchesOrigin(originGroup: OriginGroup): Boolean = when (this) {
        BUILTIN -> originGroup == OriginGroup.NATIVE
        MIHON -> originGroup == OriginGroup.MIHON
        ANIYOMI -> originGroup == OriginGroup.ANIYOMI
        LEGADO -> originGroup == OriginGroup.LEGADO_JSON
        TVBOX -> originGroup == OriginGroup.TVBOX_JSON
        IREADER -> originGroup == OriginGroup.IREADER
        CLOUDSTREAM -> originGroup == OriginGroup.CLOUDSTREAM
        LNREADER -> originGroup == OriginGroup.LNREADER_JSON
        TSUNDOKU -> originGroup == OriginGroup.TSUNDOKU
        PINNED -> true
    }

    /**
     * Check if this tag supports the given content tab.
     */
    fun supportsContentTab(tab: BrowseGroupTab): Boolean = when (this) {
        BUILTIN -> true
        MIHON -> tab == BrowseGroupTab.Content || tab == BrowseGroupTab.All
        ANIYOMI -> tab == BrowseGroupTab.Video || tab == BrowseGroupTab.All
        LEGADO -> tab == BrowseGroupTab.Content || tab == BrowseGroupTab.Novel || tab == BrowseGroupTab.All
        TVBOX -> tab == BrowseGroupTab.Video || tab == BrowseGroupTab.All
        IREADER -> tab == BrowseGroupTab.Novel || tab == BrowseGroupTab.All
        CLOUDSTREAM -> tab == BrowseGroupTab.Video || tab == BrowseGroupTab.All
        LNREADER -> tab == BrowseGroupTab.Novel || tab == BrowseGroupTab.All
        TSUNDOKU -> tab == BrowseGroupTab.Novel || tab == BrowseGroupTab.All
        PINNED -> true
    }

    companion object {
        val quickFilterEntries: List<SourceTag> = listOf(
            BUILTIN,
            MIHON,
            ANIYOMI,
            LEGADO,
            TVBOX,
            IREADER,
            CLOUDSTREAM,
            LNREADER,
            TSUNDOKU,
        )

        /**
         * The quick filter's selection step on every page: `null` ("all") clears, a selected tag is removed and
         * any other tag is added. Several tags combine with OR, as the library derivers apply them.
         */
        fun toggle(current: Set<SourceTag>, tag: SourceTag?): Set<SourceTag> = when {
            tag == null -> emptySet()
            tag in current -> current - tag
            else -> current + tag
        }

        /** The filter menu lists selected tags first and keeps [entries]' order otherwise. */
        fun menuOrder(entries: List<SourceTag>, selected: Set<SourceTag>): List<SourceTag> =
            entries.sortedBy { it !in selected }

        /** OR over [selected]; an empty selection accepts everything and an unknown origin nothing else. */
        fun accepts(selected: Set<SourceTag>, origin: OriginGroup?): Boolean =
            selected.isEmpty() || (origin != null && selected.any { it.matchesOrigin(origin) })

        fun sanitizeQuickFilterSelection(tags: Set<SourceTag>): Set<SourceTag> =
            tags.filterTo(linkedSetOf()) { it in quickFilterEntries || it == PINNED }

        fun fromIds(ids: Collection<String>): Set<SourceTag> =
            ids.mapNotNull { id ->
                when (id) {
                    "json" -> LEGADO
                    else -> entries.find { it.id == id }
                }
            }.toSet()
    }
}
