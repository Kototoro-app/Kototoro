package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.ContentType

/** Android classifies parser plugins (Kototoro, Kotatsu, UMA) as native "built-in" sources. */
internal fun SourceEcosystem.originGroup(): OriginGroup = when (this) {
    SourceEcosystem.KOTOTORO, SourceEcosystem.KOTATSU, SourceEcosystem.UMA -> OriginGroup.NATIVE
    SourceEcosystem.MIHON -> OriginGroup.MIHON
    SourceEcosystem.ANIYOMI -> OriginGroup.ANIYOMI
    SourceEcosystem.TSUNDOKU -> OriginGroup.TSUNDOKU
    SourceEcosystem.CLOUDSTREAM -> OriginGroup.CLOUDSTREAM
}

/** A source's content group from its content type, as Android's source groups classify it (NSFW wins). */
internal fun desktopContentGroup(contentType: String?, nsfw: Boolean): org.skepsun.kototoro.core.jsonsource.ContentGroup {
    val type = DesktopSourceFilter.contentTypeOf(contentType)
    val novel = BrowseGroupTab.Novel.matchesContentType(type)
    val video = BrowseGroupTab.Video.matchesContentType(type)
    val adult = nsfw || type.name.startsWith("HENTAI_")
    return when {
        novel -> if (adult) org.skepsun.kototoro.core.jsonsource.ContentGroup.HENTAI_NOVEL
            else org.skepsun.kototoro.core.jsonsource.ContentGroup.NOVEL
        video -> if (adult) org.skepsun.kototoro.core.jsonsource.ContentGroup.HENTAI_VIDEO
            else org.skepsun.kototoro.core.jsonsource.ContentGroup.VIDEO
        BrowseGroupTab.Content.matchesContentType(type) -> if (adult) org.skepsun.kototoro.core.jsonsource.ContentGroup.HENTAI_MANGA
            else org.skepsun.kototoro.core.jsonsource.ContentGroup.MANGA
        else -> org.skepsun.kototoro.core.jsonsource.ContentGroup.OTHER
    }
}

/** Android's quick filter entries that a Windows ecosystem can ever satisfy; the others would stay disabled. */
internal val DesktopSourceTagEntries: List<SourceTag> = SourceTag.quickFilterEntries.filter { tag ->
    SourceEcosystem.entries.any { tag.matchesOrigin(it.originGroup()) }
}

internal fun SourceTag.desktopTitle(): String = when (this) {
    SourceTag.BUILTIN -> "内置"
    SourceTag.MIHON -> "Mihon"
    SourceTag.ANIYOMI -> "Aniyomi"
    SourceTag.TSUNDOKU -> "Tsundoku"
    SourceTag.CLOUDSTREAM -> "Cloudstream"
    else -> id
}

private fun SourceTag.desktopIcon(): String = "icons/ic_source_$id.svg"

/** Android's top bar content-type chip values: all, manga, novel and video. */
internal val DesktopContentTabs: List<BrowseGroupTab> =
    listOf(BrowseGroupTab.All, BrowseGroupTab.Content, BrowseGroupTab.Novel, BrowseGroupTab.Video)

internal fun BrowseGroupTab.desktopTitle(): String = when (this) {
    BrowseGroupTab.Content -> "漫画"
    BrowseGroupTab.Novel -> "小说"
    BrowseGroupTab.Video -> "视频"
    else -> "全部"
}

private fun BrowseGroupTab.tag(): String = id

/**
 * The two Android top bar filters. Android hides the content-type filter while the Space switcher is on
 * (the space then fixes the content scope); Windows has no spaces yet, so it is always offered.
 */
data class DesktopSourceFilter(
    val tags: Set<SourceTag> = emptySet(),
    val tab: BrowseGroupTab = BrowseGroupTab.All,
) {
    val count: Int get() = tags.size + if (tab != BrowseGroupTab.All) 1 else 0

    fun accepts(ecosystem: SourceEcosystem?, contentType: String?): Boolean =
        SourceTag.accepts(tags, ecosystem?.originGroup()) &&
            (tab == BrowseGroupTab.All || tab.matchesContentType(contentTypeOf(contentType)))

    fun accepts(listing: SourceListing): Boolean = accepts(listing.ecosystem, listing.source.contentType)

    fun toggle(tag: SourceTag?): DesktopSourceFilter = copy(tags = SourceTag.toggle(tags, tag))

    companion object {
        /** Unrecorded types count as manga, as Android's history deriver does. */
        fun contentTypeOf(name: String?): ContentType =
            name?.let { runCatching { ContentType.valueOf(it) }.getOrNull() } ?: ContentType.MANGA

        /** Like Android, options without any matching installed source are shown but disabled. */
        fun enabledTags(sources: List<SourceListing>): Set<SourceTag> =
            DesktopSourceTagEntries.filterTo(linkedSetOf()) { tag ->
                sources.any { tag.matchesOrigin(it.ecosystem.originGroup()) }
            }

        fun enabledTabs(sources: List<SourceListing>): Set<BrowseGroupTab> =
            DesktopContentTabs.filterTo(linkedSetOf()) { tab ->
                tab == BrowseGroupTab.All || sources.any { tab.matchesContentType(contentTypeOf(it.source.contentType)) }
            }
    }
}

/** Android's right-hand top bar pill: content-type menu and source-type menu, highlighted when active. */
@Composable
internal fun DesktopSourceFilterControls(filter: DesktopSourceFilter, sources: List<SourceListing>,
    onChange: (DesktopSourceFilter) -> Unit, enabled: Boolean, tagPrefix: String) {
    val enabledTags = remember(sources) { DesktopSourceFilter.enabledTags(sources) }
    val enabledTabs = remember(sources) { DesktopSourceFilter.enabledTabs(sources) }
    DesktopControlSurface(Modifier.height(44.dp), RoundedCornerShape(percent = 50)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            var typeMenu by remember { mutableStateOf(false) }
            Box {
                TextButton({ typeMenu = true }, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-content-type"),
                    colors = ButtonDefaults.textButtonColors(contentColor =
                        if (filter.tab != BrowseGroupTab.All) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(filter.tab.desktopTitle()) }
                DropdownMenu(typeMenu && enabled, { typeMenu = false }) {
                    DesktopContentTabs.forEach { tab ->
                        DropdownMenuItem({ Text(tab.desktopTitle()) }, onClick = {
                            typeMenu = false
                            onChange(filter.copy(tab = tab))
                        }, enabled = tab in enabledTabs, modifier = Modifier.testTag("$tagPrefix-content-type:${tab.tag()}"),
                            trailingIcon = if (tab == filter.tab) {
                                { Icon(painterResource("icons/ic_check.svg"), null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)) }
                            } else null)
                    }
                }
            }
            var tagMenu by remember { mutableStateOf(false) }
            Box {
                IconButton({ tagMenu = true }, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-source-tags")) {
                    Icon(painterResource(filter.tags.singleOrNull()?.desktopIcon() ?: "icons/ic_filter_menu.svg"),
                        contentDescription = "源类型", modifier = Modifier.size(20.dp),
                        tint = if (filter.tags.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(tagMenu && enabled, { tagMenu = false }) {
                    DropdownMenuItem({ Text("全部") }, onClick = { tagMenu = false; onChange(filter.toggle(null)) },
                        leadingIcon = { Checkbox(filter.tags.isEmpty(), onCheckedChange = null) },
                        modifier = Modifier.testTag("$tagPrefix-source-tag:all"))
                    SourceTag.menuOrder(DesktopSourceTagEntries, filter.tags).forEach { tag ->
                        val selected = tag in filter.tags
                        DropdownMenuItem({ Text(tag.desktopTitle()) }, onClick = { tagMenu = false; onChange(filter.toggle(tag)) },
                            enabled = tag in enabledTags, modifier = Modifier.testTag("$tagPrefix-source-tag:${tag.id}"),
                            leadingIcon = {
                                Icon(painterResource(tag.desktopIcon()), null, modifier = Modifier.size(18.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = if (selected) {
                                { Icon(painterResource("icons/ic_check.svg"), null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)) }
                            } else null)
                    }
                }
            }
        }
    }
}
