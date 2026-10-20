package org.skepsun.kototoro.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.DuplicateGroupingRules
import org.skepsun.kototoro.migration.domain.TitleMatchingRules
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.stringByApplyingTransform

class IosDuplicateEntry internal constructor(internal val row: LibraryRow, val content: IosSourceContent,
    val sourceAvailable: Boolean) {
    val id: String get() = content.id
    val chapterCount: Int get() = row.knownChapters
    /** 0…1 of the last read chapter, when the work was read. */
    val progress: Float? get() = row.historyPercent?.takeIf { it in 0f..1f }
    /** Number of the chapter last read, formatted as the chapter list does. */
    val readChapter: String? get() = row.historyChapterNumber?.takeIf { it > 0f }?.let(::chapterNumber)
}

/** Favourites that look like one work; [recommendedId] is the entry Android would keep. */
class IosDuplicateGroup internal constructor(val key: String, val entries: List<IosDuplicateEntry>,
    val recommendedId: String,
    /** Joined through a fuzzy title match: the user should check it is really one work. */
    val fuzzy: Boolean)

/** Android's library duplicate finder over the shared schema: grouping and merge rules are shared, not reimplemented. */
class IosLibraryDuplicates internal constructor(private val library: IosLibrary) {

    /**
     * Groups not dismissed by the user ([ignoredKeys]), entries best-first. With [fuzzy], titles at least [threshold]
     * similar (0.5…1) also count; the comparison is pairwise, so it runs off the main thread.
     */
    @Throws(Exception::class)
    suspend fun groups(ignoredKeys: List<String>, fuzzy: Boolean, threshold: Double): List<IosDuplicateGroup> {
        val rows = library.database.getMigrationDao().findLibraryRows()
        val trackingIds = library.database.getMigrationDao().findLibraryTrackingIds()
        val known = library.knownSources
        val unavailable = rows.mapTo(HashSet()) { it.source }.filterTo(HashSet()) { it !in known }
        val groups = withContext(Dispatchers.Default) {
            DuplicateGroupingRules.group(rows, unavailable, ignoredKeys.toSet(), ::titleKeys, ::mediaKindOf,
                threshold.takeIf { fuzzy }?.coerceIn(0.5, 1.0), trackingIds)
        }
        val contents = library.records.findMany(groups.flatMap { g -> g.entries.map { it.row.id } }).associateBy { it.id }
        return groups.mapNotNull { group ->
            val entries = group.entries.mapNotNull { entry ->
                contents[entry.row.id]?.let { IosDuplicateEntry(entry.row, IosSourceContent(it), !entry.sourceBroken) }
            }
            if (entries.size < 2 || entries.none { it.row.id == group.recommendedId }) null
            else IosDuplicateGroup(group.key, entries, group.recommendedId.toString(), group.fuzzy)
        }
    }

    /** Whether merging into [keep] moves a duplicate's further progress, which needs [keep]'s chapter list. */
    fun needsChapters(group: IosDuplicateGroup, keep: IosDuplicateEntry): Boolean =
        progressDonor(group, keep) != null

    /**
     * Folds the group's other entries into [keep]; [details] are [keep]'s details with chapters (stored or freshly
     * loaded), or null when [needsChapters] is false and none could be read.
     */
    @Throws(Exception::class)
    suspend fun merge(group: IosDuplicateGroup, keep: IosDuplicateEntry, details: IosSourceContent?) {
        require(group.entries.any { it.row.id == keep.row.id })
        val target = details?.value ?: keep.content.value
        require(target.id == keep.row.id) { localized("保留作品的身份已变化，请重新查找重复") }
        val donor = progressDonor(group, keep)
        require(donor == null || !target.chapters.isNullOrEmpty()) {
            localized("无法读取保留作品的章节，阅读进度不能合并；请检查来源后重试")
        }
        library.records.mergeDuplicates(target, group.entries.filter { it.row.id != keep.row.id }.map { it.content.value },
            donor?.id)
    }

    private fun progressDonor(group: IosDuplicateGroup, keep: IosDuplicateEntry): LibraryRow? =
        DuplicateGroupingRules.progressDonor(keep.row, group.entries.filter { it.row.id != keep.row.id }.map { it.row })
}

/** Android's keys: NFKC, invariant lowercase, traditional folded to simplified, letters and digits only. */
@OptIn(BetaInteropApi::class)
internal fun titleKeys(title: String, altTitles: List<String>): Set<String> =
    (listOf(title) + altTitles).mapNotNullTo(HashSet()) { text ->
        val simplified = NSString.create(string = text).stringByApplyingTransform("Traditional-Simplified", false) ?: text
        val folded = NSString.create(string = simplified).precomposedStringWithCompatibilityMapping.lowercase()
        TitleMatchingRules.normalizeFoldedTitle(folded).takeIf(String::isNotEmpty)
    }
