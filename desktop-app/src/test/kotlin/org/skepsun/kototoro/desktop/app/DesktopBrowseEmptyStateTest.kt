package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Browse on Windows used to show "0 sources" over a blank area; Android's Explore says why it is empty and offers the
 * next step for that reason (§25). The probe drives the real page: no extension installed reaches the extension
 * manager, and a search that hides every source switches the sentence and resets instead.
 */
class DesktopBrowseEmptyStateTest {
    @Test
    fun browseExplainsEmptySourceList(@TempDir directory: Path) {
        DesktopProbes.run("browse-empty", directory.resolve("browse empty"), 180)
    }
}
