package org.skepsun.kototoro.history.data

import org.skepsun.kototoro.core.model.ContentHistory
import java.time.Instant

fun HistoryEntity.toContentHistory() = ContentHistory(
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    chapterId = chapterId,
    page = page,
    scroll = scroll.toInt(),
    percent = percent,
    chaptersCount = chaptersCount,
    parentChapterId = parentChapterId,
)
