package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DesktopRuntimeCheckTest {
    @Test
    fun `runtime check refuses implicit user data directories before opening a platform`() {
        assertThrows(IllegalArgumentException::class.java) { main(arrayOf("--check-runtime", "unused-report.properties")) }
    }
}
