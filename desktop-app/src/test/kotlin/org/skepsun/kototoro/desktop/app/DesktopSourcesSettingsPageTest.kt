package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class DesktopSourcesSettingsPageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun sourcesSettingsPageWritesOriginalPreferencesAndRestoresThem() {
        val root = directory.resolve("来源 设置")
        DesktopProbes.run("sources-settings-write", root, 90)
        DesktopProbes.run("sources-settings-read", root, 90)
    }
}
