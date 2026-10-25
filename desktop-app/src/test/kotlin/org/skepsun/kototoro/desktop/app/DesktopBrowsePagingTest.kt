package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's endless browse list on Windows: opening a source appends the following batches on its own until the source
 * runs out (no manual 下一页), the footer reports the end and disables the fallback button, and a search starts over.
 */
class DesktopBrowsePagingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun browsePaging() = DesktopProbes.run("browsePaging", directory.resolve("browse-paging"), 180)
}
