package org.skepsun.kototoro.local.epub

/**
 * Classification of chapter types for EPUB support
 *
 * This enum distinguishes between:
 * - Regular online chapters from manga sources
 * - EPUB download link chapters (parent chapters)
 * - EPUB internal chapters (extracted from EPUB files)
 */
enum class ChapterType {
    /**
     * Regular online chapter from a manga source
     */
    NORMAL,

    /**
     * EPUB download link chapter (parent chapter that contains an EPUB file)
     */
    EPUB_DOWNLOAD,

    /**
     * Chapter extracted from within an EPUB file (internal chapter)
     */
    EPUB_INTERNAL
}
