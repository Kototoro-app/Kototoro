package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Issue #601: with a mouse wheel or a trackpad's two-finger scroll the reader's chapter panel used to slide away
 * and dismiss on the first scroll event, so the chapter list could not be scrolled at all. The panel's wheel step
 * lives in [DesktopMangaDirectoryProbe], which drives the real reader; this class keeps it runnable on its own.
 */
class DesktopChapterPanelScrollTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `the chapter panel scrolls with the wheel and stays open`() {
        DesktopProbes.run("manga-directory", directory.resolve("中文 chapter panel scroll"), 180)
    }
}
