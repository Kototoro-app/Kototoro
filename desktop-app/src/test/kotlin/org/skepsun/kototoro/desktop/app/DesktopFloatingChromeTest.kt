package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Android's floating top chrome on the desktop shell: the home page's list reaches the top edge under its bar (it is
 * not pushed below it), and scrolling moves the rows beneath the bar. Screenshots cover Material and iOS in both
 * themes, scrolled so the rows sit under the glass controls.
 */
class DesktopFloatingChromeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `home rows scroll beneath the floating top bar in Material and iOS styles`() {
        val log = directory.resolve("floating-chrome.log")
        val process = ProcessBuilder(DesktopProbes.javaExecutable,
            "-Xmx768m", "-Dfile.encoding=UTF-8", "-cp", System.getProperty("kototoro.desktop.test.classpath"),
            DesktopFloatingChromeTest::class.java.name, directory.resolve("data").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Floating chrome probe timed out: ${Files.readString(log)}")
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun exercise(directory: Path) {
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.home().join() }
            val works = (1..30).map { index ->
                SourceContent(index.toLong(), "作品 $index", emptySet(), "fixture:$index", "fixture:$index",
                    0f, null, null, emptySet(), null, emptySet(), SourceRef("chrome-fixture", "zh", "MANGA"))
            }
            val state = controller.state.value.copy(
                homeHistory = DesktopLibrarySnapshot(works.mapIndexed { index, content ->
                    DesktopLibraryEntry(content, emptySet(), 1000L - index, 1000L - index, .25f)
                }),
                suggestions = works,
            )
            val reports = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
            for (style in DesktopInterfaceStyle.entries) for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
                runDesktopComposeUiTest(width = 1260, height = 480) {
                    setContent {
                        DesktopTheme(appearance, style) {
                            Box(Modifier.fillMaxSize()) { DesktopHomePanel(controller, state, true) }
                        }
                    }
                    waitForIdle()
                    val panel = onNodeWithTag("home-panel").fetchSemanticsNode().boundsInRoot
                    val list = onNodeWithTag("home-scroll").fetchSemanticsNode().boundsInRoot
                    assertEquals(panel.top, list.top, .5f, "$style $appearance: the list must start under the bar, not below it")
                    val before = onNodeWithTag("home-history").fetchSemanticsNode().boundsInRoot.top
                    onNodeWithTag("home-scroll").performScrollToIndex(1)
                    waitForIdle()
                    val after = onNodeWithTag("home-history").fetchSemanticsNode().boundsInRoot.top
                    assertTrue(after < before, "$style $appearance: scrolling must move the rows up ($before -> $after)")
                    val name = "floating-chrome-${style.name.lowercase()}-${appearance.name.lowercase()}.png"
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(reports.resolve(name), it.bytes) }
                    }
                }
            }
        } finally { runBlocking { controller.shutdown() } }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                DesktopFloatingChromeTest().exercise(Path.of(args[0]))
                println("DESKTOP_FLOATING_CHROME_OK")
                exitProcess(0)
            } catch (error: Throwable) {
                error.printStackTrace()
                exitProcess(1)
            }
        }
    }
}
