package org.skepsun.kototoro.tracker.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.skepsun.kototoro.tracker.data.TrackLogEntity

data class DailyUpdateGroup(
    val mangaId: Long,
    val branch: String?,
    val day: String,
    val logs: List<TrackLogEntity>,
    val chapters: List<UpdateChapter>,
    val names: List<String>,
    val discoveredAt: Long,
    /** -1 means the legacy event has no reliable chapter reading state. */
    val pendingCount: Int,
    val isUnseen: Boolean = pendingCount != 0 && logs.any { it.isUnread },
) {
    val count: Int get() = when {
        chapters.isNotEmpty() -> chapters.size
        names.isEmpty() || names.any { it == "New chapters" || it.startsWith("New chapters x ") } -> -1
        else -> names.size
    }
}

/** History is grouped per local discovery day; pending chapters remain a separate work-level aggregation. */
fun dailyUpdateGroups(logs: List<TrackLogEntity>, zone: TimeZone = TimeZone.currentSystemDefault()): List<DailyUpdateGroup> {
    fun day(time: Long) = Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).date.toString()
    return logs.groupBy { it.mangaId }.flatMap { (mangaId, ownerLogs) ->
        val modern = ownerLogs.filter { it.chapterMetadata.isNotBlank() }
        val parsed = modern.associateWith { decodeUpdateChapters(it.chapterMetadata) }
        val snapshots = mergeUpdateChapters(parsed.values.toList())
        val unseenByKey = HashMap<String, Boolean>()
        parsed.forEach { (log, chapters) -> chapters.forEach { chapter ->
            unseenByKey[chapter.key] = (unseenByKey[chapter.key] ?: true) && log.isUnread
        } }
        val known = snapshots.groupBy { it.branch to day(it.discoveredAt) }.map { (key, chapters) ->
            val keys = chapters.mapTo(HashSet()) { it.key }
            val members = modern.filter { log -> parsed.getValue(log).any { it.key in keys } }
            DailyUpdateGroup(mangaId, key.first, key.second, members, chapters, chapters.map { it.title },
                chapters.maxOf { it.discoveredAt }, chapters.count { it.readAt == 0L },
                chapters.any { it.readAt == 0L && unseenByKey[it.key] == true })
        }
        val legacy = ownerLogs.filter { it.chapterMetadata.isBlank() }.groupBy { day(it.createdAt) }
            .map { (date, members) ->
                DailyUpdateGroup(mangaId, null, date, members, emptyList(),
                    members.flatMap { it.chapters.lines().filter(String::isNotBlank) }.distinct(),
                    members.maxOf { it.createdAt }, -1)
            }
        known + legacy
    }.sortedByDescending { it.discoveredAt }
}
