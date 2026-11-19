package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's `reader_chapter_break` on Windows: the continuous reader shows the shared chapter break card after the last
 * page only once the reader settings page turns the setting on.
 */
class DesktopReaderChapterBreakTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `continuous reader shows the chapter break card from the reader settings`() {
        DesktopProbes.run("reader-chapter-break", directory.resolve("中文 chapter break"), 150)
    }
}
