package org.skepsun.kototoro.history.data

// Use the reading branch when known. The furthest position is kept while re-reading earlier chapters.
internal const val UNREAD_CHAPTER_POSITION_JOINS = """
    LEFT JOIN chapters current_chapter ON current_chapter.manga_id = wh.manga_id
        AND current_chapter.chapter_id = COALESCE(wh.parent_chapter_id, wh.chapter_id)
    LEFT JOIN chapters furthest_chapter ON furthest_chapter.manga_id = wh.manga_id
        AND furthest_chapter.chapter_id = wh.furthest_chapter_id
        AND (current_chapter.chapter_id IS NULL OR furthest_chapter.branch IS current_chapter.branch)
"""

// Same number/volume order as chapterIsAfter. A missing position falls back to saved progress in the domain.
internal const val UNREAD_CHAPTER_COUNT = """
    CASE
        WHEN wh.manga_id IS NULL THEN (
            SELECT COUNT(*) FROM chapters c WHERE c.manga_id = m.manga_id
            GROUP BY c.branch ORDER BY COUNT(*) DESC LIMIT 1
        )
        WHEN current_chapter.chapter_id IS NOT NULL OR furthest_chapter.chapter_id IS NOT NULL THEN (
            SELECT COUNT(*) FROM chapters c
            WHERE c.manga_id = wh.manga_id
                AND c.branch IS CASE WHEN current_chapter.chapter_id IS NOT NULL
                    THEN current_chapter.branch ELSE furthest_chapter.branch END
                AND (current_chapter.chapter_id IS NULL OR CASE
                    WHEN c.volume > 0 AND current_chapter.volume > 0 AND c.volume != current_chapter.volume
                    THEN c.volume > current_chapter.volume ELSE c.number > current_chapter.number END)
                AND (furthest_chapter.chapter_id IS NULL OR CASE
                    WHEN c.volume > 0 AND furthest_chapter.volume > 0 AND c.volume != furthest_chapter.volume
                    THEN c.volume > furthest_chapter.volume ELSE c.number > furthest_chapter.number END)
        )
    END
"""
