package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopPlatformTest {
    @Test
    fun `native upscaling has a pinned asset for each supported desktop platform`() {
        for (platform in DesktopPlatform.entries) {
            for (tool in DesktopUpscaleTool.entries) {
                assertEquals(platform.supportsNativeUpscaling, tool.asset(platform) != null, "$platform $tool")
                tool.asset(platform)?.let { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
            }
        }
    }
    @Test
    fun `Windows package capability never leaks to other hosts`() {
        assertTrue(DesktopPlatform.detect("Windows 11").supportsWindowsPackages)
        for (name in listOf("Mac OS X", "Darwin", "Linux", "FreeBSD", "")) {
            assertFalse(DesktopPlatform.detect(name).supportsWindowsPackages, name)
        }
        assertEquals(DesktopPlatform.MACOS, DesktopPlatform.detect("Mac OS X"))
        assertEquals("macOS", DesktopPlatform.detect("Darwin").displayName)
        assertEquals(DesktopPlatform.LINUX, DesktopPlatform.detect("Linux"))
        assertEquals(DesktopPlatform.OTHER, DesktopPlatform.detect("FreeBSD"))
    }
}
