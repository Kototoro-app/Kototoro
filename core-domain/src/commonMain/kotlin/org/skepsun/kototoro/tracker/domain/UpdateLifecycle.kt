package org.skepsun.kototoro.tracker.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
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
suspend fun MangaDatabase.recordUpdateBatch(mangaId: Long, chapterIds: Set<Long>, now: Long): Int {
    val dao = getTrackLogsDao()
    val previous = dao.findByOwner(mangaId)
    val known = previous.flatMap { decodeUpdateChapters(it.chapterMetadata) }.mapTo(HashSet()) { it.key }
    val added = getChaptersDao().findAll(mangaId).filter { it.chapterId in chapterIds }
        .distinctBy { it.updateKey() }.filterNot { it.updateKey() in known }
        .map { UpdateChapter(it.updateKey(), it.chapterId, it.title, it.branch, it.uploadDate, now) }
    if (added.isNotEmpty()) {
        dao.insert(TrackLogEntity(mangaId = mangaId, chapters = added.joinToString("\n") { it.title },
            createdAt = now, isUnread = true, chapterMetadata = encodeUpdateChapters(added)))
    }
    refreshPendingUpdateCount(mangaId)
    return added.size
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

/** Reading a chapter's final page completes that chapter, without treating skipped chapters as read. */
suspend fun MangaDatabase.completeUpdateChapter(mangaId: Long, chapterId: Long, now: Long) {
    val chapter = getChaptersDao().findAll(mangaId).firstOrNull { it.chapterId == chapterId } ?: return
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
