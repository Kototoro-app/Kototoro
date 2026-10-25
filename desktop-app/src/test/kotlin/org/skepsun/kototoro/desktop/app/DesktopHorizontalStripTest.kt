package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's continuous horizontal reading mode on the Windows reader: one row of pages fitted to the window height, the
 * wheel moving it, history progress from the same scene, and `reader_continuous_horizontal_reversed` flipping the row
 * the way the phone does. Both settings survive closing the chapter.
 */
internal class DesktopHorizontalStripTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun continuousHorizontalStripReadsAndReverses() {
        DesktopProbes.run("reader-horizontal-strip", directory.resolve("中文 horizontal strip"), 240)
    }
}
