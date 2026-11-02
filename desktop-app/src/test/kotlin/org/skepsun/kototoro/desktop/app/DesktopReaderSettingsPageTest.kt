package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class DesktopReaderSettingsPageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `reader settings edit shared preferences open rule pages and survive process restart`() {
        val root = directory.resolve("阅读 设置")
        DesktopProbes.run("reader-settings-edit", root, 90)
        DesktopProbes.run("reader-settings-read", root, 90)
    }
}
