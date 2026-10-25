package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Android's "autodetect reader mode" (`reader_mode_detect`) on the Windows reader: the page at 30% of the chapter
 * decides a work is a webtoon, the switch gates that, and the verdict is remembered per work.
 */
internal class DesktopModeDetectTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun detectingTheReaderModeOpensWebtoonsAsAStrip() {
        // The fixture's 30% page becomes a 400x2400 webtoon page; only this probe asks for it. `DesktopProbes` forwards
        // every `fixture.*` property to the probe JVM, so the switch is restored: otherwise later probes in the same
        // test JVM would open a webtoon and, for example, the preload window assertions would see a strip.
        val previous = System.getProperty(TALL_PROBE)
        System.setProperty(TALL_PROBE, "true")
        try {
            DesktopProbes.run("reader-mode-detect", directory.resolve("中文 mode detect"), 180)
        } finally {
            if (previous == null) System.clearProperty(TALL_PROBE) else System.setProperty(TALL_PROBE, previous)
        }
    }

    private companion object {
        const val TALL_PROBE = "fixture.reader.probe.tall"
    }
}
