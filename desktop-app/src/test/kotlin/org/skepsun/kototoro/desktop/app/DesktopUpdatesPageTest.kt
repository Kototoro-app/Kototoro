package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's updates destination on Windows, end to end: the subscriptions carousel's "all" opens it, the header counts
 * the waiting works, and all three drain paths (opening a card, a row's 移除, the header's 全部标为已读) leave the
 * history and the reading progress alone.
 */
class DesktopUpdatesPageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun updatesPage() = DesktopProbes.run("updates", directory.resolve("updates"), 180)
}
