package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's "chapter title at the bottom" (`reader_chapter_title_bottom`) on the Windows reader: the chapter pill starts
 * in the top bar, moves over the progress dock when the switch is on (exactly one pill, measured on the real reader) and
 * the switch on the reader settings page writes the same preference Android does.
 */
internal class DesktopReaderTitleBottomTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun chapterTitleMovesToTheBottomAndBack() {
        DesktopProbes.run("reader-title-bottom", directory.resolve("中文 chapter title"), 150)
    }
}
