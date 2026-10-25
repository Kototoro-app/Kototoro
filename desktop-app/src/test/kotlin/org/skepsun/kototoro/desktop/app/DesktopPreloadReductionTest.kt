package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's "reduce page preloading" (`reader_reduce_offscreen_quality`) on the Windows reader: the paged reader keeps
 * two slots ahead by default and one with the switch on, and both the shared options panel row and the reader settings
 * page write the same preference Android uses.
 */
internal class DesktopPreloadReductionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun reducingPreloadingKeepsFewerPagesLoaded() {
        DesktopProbes.run("preload-reduction", directory.resolve("中文 page cache"), 150)
    }
}
