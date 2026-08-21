package org.skepsun.kototoro.local.epub

import org.skepsun.kototoro.parsers.model.ContentChapter

/**
 * Extended metadata for ContentChapter to support EPUB functionality
 *
 * This class holds EPUB-specific metadata that cannot be stored directly
 * in the parsers library's ContentChapter model.
 *
 * Usage:
 * - Store in a separate map/cache keyed by chapter ID
 * - Use extension functions to access metadata
 */
data class ChapterMetadata(
    /**
     * The chapter ID this metadata belongs to
     */
    val chapterId: Long,

    /**
     * Type classification of the chapter
     */
    val chapterType: ChapterType = ChapterType.NORMAL,

    /**
     * ID of the parent EPUB download chapter (for EPUB_INTERNAL chapters)
     * Null for NORMAL and EPUB_DOWNLOAD chapters
     */
    val parentChapterId: Long? = null,

    /**
     * Display name of the EPUB file (for grouping EPUB_INTERNAL chapters)
     * Null for NORMAL chapters
     */
    val epubFileName: String? = null
)

/**
 * Extension function to create ChapterMetadata from a ContentChapter
 */
fun ContentChapter.toMetadata(
    chapterType: ChapterType = ChapterType.NORMAL,
    parentChapterId: Long? = null,
    epubFileName: String? = null
): ChapterMetadata {
    return ChapterMetadata(
        chapterId = this.id,
        chapterType = chapterType,
        parentChapterId = parentChapterId,
        epubFileName = epubFileName
    )
}

/**
 * Wrapper class that combines ContentChapter with its metadata
 *
 * This provides a convenient way to work with chapters and their EPUB metadata together.
 */
data class ChapterWithMetadata(
    val chapter: ContentChapter,
    val metadata: ChapterMetadata
) {
    /**
     * Convenience property to access chapter type
     */
    val chapterType: ChapterType
        get() = metadata.chapterType

    /**
     * Convenience property to access parent chapter ID
     */
    val parentChapterId: Long?
        get() = metadata.parentChapterId

    /**
     * Convenience property to access EPUB filename
     */
    val epubFileName: String?
        get() = metadata.epubFileName
}

/**
 * Extension function to combine a ContentChapter with metadata
 */
fun ContentChapter.withMetadata(metadata: ChapterMetadata): ChapterWithMetadata {
    return ChapterWithMetadata(this, metadata)
}

/**
 * Extension function to combine a ContentChapter with inline metadata
 */
fun ContentChapter.withMetadata(
    chapterType: ChapterType = ChapterType.NORMAL,
    parentChapterId: Long? = null,
    epubFileName: String? = null
): ChapterWithMetadata {
    return ChapterWithMetadata(
        chapter = this,
        metadata = ChapterMetadata(
            chapterId = this.id,
            chapterType = chapterType,
            parentChapterId = parentChapterId,
            epubFileName = epubFileName
        )
    )
}
