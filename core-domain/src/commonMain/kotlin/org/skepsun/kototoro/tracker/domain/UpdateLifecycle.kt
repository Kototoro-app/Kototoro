package org.skepsun.kototoro.tracker.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity

/** A chapter's immutable discovery snapshot and monotonic completed-reading state. */
@Serializable
data class UpdateChapter(
    val key: String,
    val chapterId: Long,
    val title: String,
    val branch: String?,
    val publishedAt: Long,
    val discoveredAt: Long,
    val readAt: Long = 0,
)

private val updateJson = Json { ignoreUnknownKeys = true }

fun decodeUpdateChapters(metadata: String): List<UpdateChapter> =
    if (metadata.isBlank()) emptyList() else updateJson.decodeFromString(metadata)

fun encodeUpdateChapters(chapters: List<UpdateChapter>): String = updateJson.encodeToString(chapters)

fun updateChapterKey(source: String, branch: String?, url: String, chapterId: Long): String =
    listOf(source, branch.orEmpty(), url.ifBlank { "id:$chapterId" })
    .joinToString("") { "${it.length}:$it" }

fun ChapterEntity.updateKey(): String = updateChapterKey(source, branch, url, chapterId)

/** Union copies of the same discovery without resurrecting read chapters or moving discovery dates. */
fun mergeUpdateChapters(groups: List<List<UpdateChapter>>): List<UpdateChapter> = groups.flatten()
    .groupBy { it.key }.values.map { versions ->
        versions.minWith(compareBy<UpdateChapter> { it.discoveredAt }.thenBy { it.chapterId }
            .thenBy { it.title }.thenBy { it.publishedAt }).copy(readAt = versions.maxOf { it.readAt })
    }.sortedWith(compareBy<UpdateChapter> { it.discoveredAt }.thenBy { it.key })

/** All database helpers in this file run inside the caller's write transaction. */
suspend fun MangaDatabase.recordUpdateBatch(mangaId: Long, chapterIds: Set<Long>, now: Long): Int =
    recordUpdateChapters(mangaId, getChaptersDao().findAll(mangaId).filter { it.chapterId in chapterIds }, now)

/**
 * [recordUpdateBatch] with the new [chapters] given rather than looked up in the work's stored chapter list. A local
 * copy of a remote work has no stored list; its own chapters keep the remote source, URL and id, so the keys of the
 * remote chapters given here are the keys its reading completes.
 */
suspend fun MangaDatabase.recordUpdateChapters(mangaId: Long, chapters: List<ChapterEntity>, now: Long): Int {
    val dao = getTrackLogsDao()
    val previous = dao.findByOwner(mangaId)
    val known = previous.flatMap { decodeUpdateChapters(it.chapterMetadata) }.mapTo(HashSet()) { it.key }
    val added = chapters
        .distinctBy { it.updateKey() }.filterNot { it.updateKey() in known }
        .map { UpdateChapter(it.updateKey(), it.chapterId, it.title, it.branch, it.uploadDate, now) }
    if (added.isNotEmpty()) {
        dao.insert(TrackLogEntity(mangaId = mangaId, chapters = added.joinToString("\n") { it.title },
            createdAt = now, isUnread = true, chapterMetadata = encodeUpdateChapters(added)))
    }
    refreshPendingUpdateCount(mangaId)
    return added.size
}

/**
 * A chapter re-uploaded under a new identity keeps its update: the recorded chapters that are keys of [moves] take
 * the identity of the chapter they moved to, with their discovery and read times unchanged.
 */
suspend fun MangaDatabase.rekeyUpdateChapters(mangaId: Long, moves: Map<ChapterEntity, ChapterEntity>) {
    val targets = moves.entries.associate { (from, to) -> from.updateKey() to to }
    val dao = getTrackLogsDao()
    for (log in dao.findByOwner(mangaId)) {
        val chapters = decodeUpdateChapters(log.chapterMetadata)
        if (chapters.none { it.key in targets }) continue
        dao.updateChapterMetadata(log.id, encodeUpdateChapters(chapters.map { chapter ->
            targets[chapter.key]?.let { to ->
                chapter.copy(key = to.updateKey(), chapterId = to.chapterId, title = to.title)
            } ?: chapter
        }))
    }
}

/** Known chapter identities, rather than notification flags or historic event totals, own the badge. */
suspend fun MangaDatabase.refreshPendingUpdateCount(mangaId: Long) {
    val chapters = getTrackLogsDao().findByOwner(mangaId).flatMap { decodeUpdateChapters(it.chapterMetadata) }
    if (chapters.isNotEmpty()) {
        val current = getChaptersDao().findAll(mangaId).mapTo(HashSet()) { it.updateKey() }
        val pending = mergeUpdateChapters(listOf(chapters)).count {
            it.readAt == 0L && (current.isEmpty() || it.key in current)
        }
        getTracksDao().updateCounter(mangaId, pending)
    }
}

/** An independently detected copy of an already seen chapter must not resurrect its reminder after sync. */
suspend fun MangaDatabase.reconcileUpdateNotifications(mangaId: Long) {
    val dao = getTrackLogsDao()
    val logs = dao.findByOwner(mangaId).associateWith { decodeUpdateChapters(it.chapterMetadata) }
    val seen = logs.flatMap { (log, chapters) -> chapters.filter { !log.isUnread || it.readAt > 0 } }
        .mapTo(HashSet()) { it.key }
    logs.forEach { (log, chapters) ->
        if (log.isUnread && chapters.isNotEmpty() && chapters.all { it.key in seen }) dao.markAsRead(log.id)
    }
}

/**
 * Reading a chapter's final page completes that chapter, without treating skipped chapters as read. [chapters] is
 * the reader's list, used when the work has no stored one (a local copy, see [recordUpdateChapters]).
 */
suspend fun MangaDatabase.completeUpdateChapter(
    mangaId: Long,
    chapterId: Long,
    now: Long,
    chapters: List<ChapterEntity> = emptyList(),
) {
    val chapter = getChaptersDao().findAll(mangaId).firstOrNull { it.chapterId == chapterId }
        ?: chapters.firstOrNull { it.chapterId == chapterId }
        ?: return
    val key = chapter.updateKey()
    val dao = getTrackLogsDao()
    var changed = false
    for (log in dao.findByOwner(mangaId)) {
        val chapters = decodeUpdateChapters(log.chapterMetadata)
        if (chapters.any { it.key == key && it.readAt == 0L }) {
            val updated = chapters.map { if (it.key == key) it.copy(readAt = now) else it }
            dao.updateChapterMetadata(log.id, encodeUpdateChapters(updated))
            dao.markAsRead(log.id)
            changed = true
        }
    }
    if (changed) refreshPendingUpdateCount(mangaId)
}

/** Explicit dismiss/mark-all actions complete pending updates, while preserving their history. */
suspend fun MangaDatabase.completePendingUpdates(mangaId: Long, now: Long) {
    val dao = getTrackLogsDao()
    for (log in dao.findByOwner(mangaId)) {
        val chapters = decodeUpdateChapters(log.chapterMetadata)
        if (chapters.any { it.readAt == 0L }) dao.updateChapterMetadata(log.id,
            encodeUpdateChapters(chapters.map { if (it.readAt == 0L) it.copy(readAt = now) else it }))
    }
    getTracksDao().clearCounter(mangaId)
    dao.markUnreadAsReadByOwner(mangaId)
}

/**
 * The subscriptions page's "mark all read": every tracked work's pending updates complete as in
 * [completePendingUpdates], and reminders left by works no longer tracked stop counting as unread too. History and
 * the timeline's rows stay. Callers run it in one write transaction.
 */
suspend fun MangaDatabase.completeAllPendingUpdates(now: Long) {
    for (mangaId in getTracksDao().findAllIds()) completePendingUpdates(mangaId, now)
    getTrackLogsDao().markAllAsSeen()
}

/**
 * Android's `TrackingRepository.updateReadChapter` fallback for counters written before v86: the badge counts
 * chapters but its log carries no identities, so there is nothing for [completeUpdateChapter] to complete and
 * reading could never lower it. The position in the branch stands in for those identities: the counter becomes the
 * chapters after the one being read, so reaching the end of the branch clears it. Counters whose logs carry
 * identities are left alone, because those rows own the badge chapter by chapter.
 *
 * Called with the same reading position Android's history save uses; returns the counter it left, or null when the
 * row is untouched because nothing about the position changed the answer.
 */
suspend fun MangaDatabase.recomputeLegacyUpdateCounter(
    mangaId: Long,
    chapterId: Long,
): Int? {
    val track = getTracksDao().find(mangaId) ?: return null
    if (track.newChapters == 0) return null
    val dao = getTrackLogsDao()
    if (dao.findByOwner(mangaId).any { it.chapterMetadata.isNotBlank() }) return null
    val chapters = getChaptersDao().findAll(mangaId)
    val current = chapters.firstOrNull { it.chapterId == chapterId } ?: return null
    val branch = chapters.filter { it.branch == current.branch }
    val index = branch.indexOfFirst { it.chapterId == chapterId }
    if (index < 0) return null
    val state = trackAfterReading(
        readIndex = index,
        anchorIndex = branch.indexOfFirst { it.chapterId == track.lastChapterId },
        lastIndex = branch.lastIndex,
        pending = track.newChapters,
    )
    val anchor = branch[state.anchorIndex]
    val pending = state.pending
    if (pending == track.newChapters && anchor.chapterId == track.lastChapterId) return null
    getTracksDao().upsert(TrackEntity(
        mangaId = track.mangaId,
        lastChapterId = anchor.chapterId,
        newChapters = pending,
        lastCheckTime = track.lastCheckTime,
        lastChapterDate = track.lastChapterDate,
        lastResult = track.lastResult,
        lastError = track.lastError,
    ))
    if (pending == 0) dao.markUnreadAsReadByOwner(mangaId)
    return pending
}

/**
 * Reconciles badges that predate chapter identities with the reading position already stored. Reading is the only
 * thing that lowers such a counter, so a device that finished these works while the counters were identity-less is
 * left with badges nothing will ever clear; the next check repairs them from the position the reader left behind.
 * The stored position is only a proxy for what was read, so a chapter that is no longer listed leaves the row alone,
 * and rows whose logs carry identities belong to [completeUpdateChapter] and are never touched here.
 */
suspend fun MangaDatabase.repairLegacyUpdateCounters(): Int {
    var repaired = 0
    for (track in getTracksDao().findUpdated()) {
        val history = getHistoryDao().find(track.mangaId) ?: continue
        if (recomputeLegacyUpdateCounter(track.mangaId, history.chapterId) != null) repaired++
    }
    return repaired
}
