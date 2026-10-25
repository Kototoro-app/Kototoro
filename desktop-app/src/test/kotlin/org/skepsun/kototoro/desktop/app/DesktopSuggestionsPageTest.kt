package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's suggestions destination on Windows, end to end: the home rail's "all" opens the page the worker stored,
 * the quick-filter chips narrow the grid the way Android's SQL condition does (an adult chip hides adult works, the
 * same chip tapped again brings them back), 换一批 rebuilds through the page, 推荐设置 reaches the settings page, and a
 * card opens the work and returns to the same list.
 */
class DesktopSuggestionsPageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun suggestionsPage() = DesktopProbes.run("suggestions", directory.resolve("suggestions"), 180)
}
