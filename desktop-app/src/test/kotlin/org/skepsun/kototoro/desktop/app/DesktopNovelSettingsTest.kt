package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class DesktopNovelSettingsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun novelOptionsUseSharedControlsAndSurviveRestart() {
        val root = directory.resolve("小说 设置")
        DesktopProbes.run("novel-settings-write", root, 90)
        DesktopProbes.run("novel-settings-read", root, 90)
    }
}
