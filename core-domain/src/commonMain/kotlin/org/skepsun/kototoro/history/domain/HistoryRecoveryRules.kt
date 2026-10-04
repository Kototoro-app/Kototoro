package org.skepsun.kototoro.history.domain

/** Returns a replacement only for a missing remote chapter without a distinct EPUB parent. */
fun recoverHistoryChapterId(
    isLocal: Boolean,
    chapterId: Long,
    parentChapterId: Long?,
    percent: Float,
    chapterIds: List<Long>?,
): Long? {
    if (isLocal || chapterIds.isNullOrEmpty() || chapterId in chapterIds) {
        return null
    }
    if (parentChapterId != null && parentChapterId != chapterId) {
        return null
    }
    // Preserve the history recovery rule: out-of-range progress does not clamp to the last chapter.
    return chapterIds.getOrNull((chapterIds.size * percent).toInt())
}
