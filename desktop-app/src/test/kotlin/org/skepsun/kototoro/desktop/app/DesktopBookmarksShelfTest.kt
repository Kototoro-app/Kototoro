package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's bookmarks page over two real processes: the first one saves a position, the second one finds it on the
 * shelf, opens it incognito and removes it through the selection bar.
 */
class DesktopBookmarksShelfTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `the shelf groups saved positions opens one incognito and removes a selection`() {
        val root = directory.resolve("中文 bookmarks shelf")
        DesktopProbes.run("bookmarks-shelf-write", root)
        DesktopProbes.run("bookmarks-shelf-read", root)
    }
}
