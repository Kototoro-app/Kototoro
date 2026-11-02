package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's "gaps in webtoon mode" on Windows: the continuous reader stacks pages flush until the reader settings page
 * turns the shared `webtoon_gaps` setting on, which inserts Android's 24dp gap between pages without reloading them.
 */
class DesktopReaderWebtoonGapsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `continuous pages gain the webtoon gap from the reader settings`() {
        DesktopProbes.run("reader-webtoon-gaps", directory.resolve("中文 webtoon gaps"), 150)
    }
}
