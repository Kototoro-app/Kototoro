package org.skepsun.kototoro.reader.data

import android.util.Log
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

fun Content.filterChapters(branch: String?): Content {
    if (chapters.isNullOrEmpty()) return this
    val filtered = chapters?.filter { it.branch == branch }
    Log.d(
        "ContentBranch",
        "filterChapters: contentId=$id requestedBranch=$branch, before=${chapters?.size ?: 0}, after=${filtered?.size ?: 0}, groups=${chapters.orEmpty().groupBy { it.branch }.mapValues { it.value.size }}",
    )
    return withChapters(chapters = filtered)
}

private fun Content.withChapters(chapters: List<ContentChapter>?) = copy(
    chapters = chapters,
)
