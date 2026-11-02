package org.skepsun.kototoro.scrobbling.common.domain

import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.tracking.domain.TrackingChapter

internal fun resolveAbsoluteChapterNumber(chapters: List<ContentChapter>, targetChapter: ContentChapter): Int =
    org.skepsun.kototoro.tracking.domain.resolveAbsoluteChapterNumber(
        chapters.map { TrackingChapter(it.id, it.number, it.branch, it.title, it.url) },
        TrackingChapter(targetChapter.id, targetChapter.number, targetChapter.branch, targetChapter.title, targetChapter.url),
    )
