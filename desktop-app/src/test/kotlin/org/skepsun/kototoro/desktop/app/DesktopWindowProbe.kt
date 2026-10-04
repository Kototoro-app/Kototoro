package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.awt.Window
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Tests this application's real native window and production close callback without controlling other apps. */
internal object DesktopWindowProbe {
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val failure = AtomicReference<Throwable>()
        val watchdog = Thread({
            try {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
                var window: Window? = null
                while (window == null) {
                    SwingUtilities.invokeAndWait { window = Window.getWindows().firstOrNull { it.isShowing } }
                    check(System.nanoTime() < deadline) { "Production window did not open" }
                    Thread.sleep(50)
                }
                if (mode == "window") {
                    while (!importRecorded(root)) {
                        check(System.nanoTime() < deadline) { "Production import did not persist" }
                        Thread.sleep(50)
                    }
                    Thread.sleep(500)
                }
                SwingUtilities.invokeAndWait {
                    check(requireNotNull(window).width >= 920)
                    check(requireNotNull(window).height >= 620)
                    window!!.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                }
            } catch (error: Throwable) { failure.set(error) }
        }, "Desktop-window-test").apply { isDaemon = true; start() }
        main(arrayOf("--data-dir", root.toString(), "--import", args[2]))
        watchdog.join(1000)
        failure.get()?.let { throw it }
        check(!watchdog.isAlive) { "Window watchdog remained active" }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        val moved = root.resolveSibling("$mode-closed")
        Files.move(root, moved)
        Files.move(moved, root)
        println("DESKTOP_UI_OK=$mode")
    }

    private fun importRecorded(root: Path): Boolean {
        val preferences = root.resolve("preferences")
        if (!Files.isDirectory(preferences)) return false
        return Files.list(preferences).use { paths -> paths.anyMatch {
            it.fileName.toString().endsWith(".json") && Files.readString(it).contains("fixture.desktop")
        } }
    }
}
