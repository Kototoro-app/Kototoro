package org.skepsun.kototoro.desktop.compat

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Check release within the generated test root before JUnit attempts its own directory cleanup. */
internal fun awaitBrowserProfileRelease(root: Path) {
    val owned = root.toAbsolutePath().normalize()
    val moved = owned.resolveSibling("${owned.fileName}-released")
    check(moved.parent == owned.parent && moved != owned)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (true) {
        try { Files.move(owned, moved); break } catch (error: IOException) {
            if (System.nanoTime() >= deadline) throw error
            Thread.sleep(100)
        }
    }
    Files.move(moved, owned)
}
