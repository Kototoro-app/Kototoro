package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.jetbrains.skia.Image

class DesktopHomeMouseGesturesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `home history and recommendations move under mouse dragging without opening a work`() {
        // The SDK's process-global Android runtime uses the same isolated JVM/classpath as the other UI probes.
        val log = directory.resolve("home-mouse.log")
        val process = ProcessBuilder(DesktopProbes.javaExecutable,
            "-Xmx768m", "-Dfile.encoding=UTF-8", "-cp", System.getProperty("kototoro.desktop.test.classpath"),
            DesktopHomeMouseGesturesTest::class.java.name, directory.resolve("data").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Home mouse probe timed out: ${Files.readString(log)}")
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
                    0f, null, null, emptySet(), null, emptySet(), SourceRef("gesture-fixture", "zh", "MANGA"))
            }
            val state = controller.state.value.copy(
                homeHistory = DesktopLibrarySnapshot(works.mapIndexed { index, content ->
                    DesktopLibraryEntry(content, emptySet(), 1000L - index, 1000L - index, .25f)
                }),
                suggestions = works,
            )
            runDesktopComposeUiTest(width = 1260, height = 620) {
                setContent {
                    DesktopTheme(DesktopAppearance.LIGHT) {
                        Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                            DesktopHomePanel(controller, state, true)
                        }
                    }
                }
                fun position(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config[
                    SemanticsProperties.HorizontalScrollAxisRange].value()
                for (tag in listOf("home-history", "home-recommendations")) {
                    onNodeWithTag("home-scroll").performScrollToNode(hasTestTag(tag))
                    val before = position(tag)
                    onNodeWithTag(tag).performMouseInput {
                        moveTo(center); press()
                        repeat(10) { advanceEventTime(16); moveBy(Offset(-30f, 0f)) }
                        release()
                    }
                    waitForIdle()
                    assertTrue(position(tag) > before, "$tag must move when a cover is grabbed and dragged left")
                    assertEquals(DesktopScreen.HOME, controller.state.value.screen, "Dragging must not open details")
                    assertNull(controller.state.value.content)
                    val report = Files.createDirectories(Path.of("build/reports/desktop-smoke")).resolve("$tag-mouse-drag.png")
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(report, it.bytes) }
                    }
                    val forward = position(tag)
                    onNodeWithTag(tag).performMouseInput {
                        moveTo(center); press()
                        repeat(10) { advanceEventTime(16); moveBy(Offset(30f, 0f)) }
                        release()
                    }
                    waitForIdle()
                    assertTrue(position(tag) < forward, "$tag must also drag back to earlier covers")
                    assertEquals(DesktopScreen.HOME, controller.state.value.screen)
                }
            }
        } finally { runBlocking { controller.shutdown() } }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                DesktopHomeMouseGesturesTest().exercise(Path.of(args[0]))
                println("DESKTOP_HOME_MOUSE_OK")
                exitProcess(0)
            } catch (error: Throwable) {
                error.printStackTrace()
                exitProcess(1)
            }
        }
    }
}
