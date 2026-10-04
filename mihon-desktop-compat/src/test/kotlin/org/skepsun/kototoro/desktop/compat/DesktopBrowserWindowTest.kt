package org.skepsun.kototoro.desktop.compat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class DesktopBrowserWindowTest {
    @Test
    fun `visible browser renders resizes hides and survives the native close button path`() = runBlocking<Unit> {
        val browser = bridge()
        browser.use {
            browser.start()
            assertFalse(browser.windowState().visible)
            val window = browser.setWindowVisible(true, width = 960, height = 640)
            assertTrue(window.visible)
            assertTrue(browser.loadHtml("""
                <!doctype html><meta charset="utf-8"><title>中文交互页面</title>
                <style>html,body{margin:0;min-height:100%;background:rgb(24,72,96);color:white}
                button{margin:40px;padding:20px;font-size:24px}</style>
                <button id="confirm" onclick="document.title='交互完成';this.innerText='已确认'">确认交互</button>
            """.trimIndent()))
            val before = browser.evaluateJs("innerWidth").toInt()
            assertTrue(before > 0)
            val report = Files.createDirectories(Path.of("build/reports/browser-window")).resolve("visible-page.png")
            withTimeout(5000) {
                while (true) {
                    val png = browser.capturePreview()
                    val image = ImageIO.read(ByteArrayInputStream(png))
                    val state = browser.windowState()
                    assertEquals(state.width, image.width)
                    assertEquals(state.height, image.height)
                    if ((image.getRGB(image.width / 2, image.height / 2) and 0xffffff) == 0x184860) {
                        Files.write(report, png)
                        break
                    }
                    delay(50)
                }
            }
            assertTrue(browser.windowState().title.contains("中文交互页面"))
            browser.setWindowVisible(true, width = 720, height = 480)
            withTimeout(3000) { while (browser.evaluateJs("innerWidth").toInt() >= before) delay(50) }
            assertFalse(browser.setWindowVisible(false).visible)
            assertTrue(browser.isRunning)
            assertEquals("\"交互完成\"", browser.evaluateJs("document.getElementById('confirm').click();document.title"))
            assertTrue(browser.setWindowVisible(true).visible)
            assertFalse(browser.dismissWindow().visible)
            assertTrue(browser.isRunning)
            assertEquals("\"交互完成\"", browser.evaluateJs("document.title"))
            assertTrue(browser.setWindowVisible(true).visible)
        }
        assertEquals(0, browser.processExitCode)
        assertFalse(browser.isRunning)
        assertEquals(0, browser.pendingRequestCount)
    }

    @Test
    fun `HTTP error pages require an explicit interaction opt in while network errors still fail`() = runBlocking<Unit> {
        bridge().use { browser ->
            browser.start()
            val address = LocalBrowserSite(status = 403).use { site ->
                assertTrue(runCatching { browser.navigate(site.url("/challenge")) }.isFailure)
                assertTrue(browser.navigate(site.url("/challenge"), allowHttpErrorResponse = true))
                assertEquals("loopback", browser.document().title)
                site.url("/closed-server")
            }
            assertTrue(runCatching { browser.navigate(address, allowHttpErrorResponse = true) }.isFailure)
        }
    }

    @Test
    fun `window controls remain responsive while navigation awaits a server response`() = runBlocking<Unit> {
        LocalBrowserSite().use { site ->
            val browser = bridge()
            browser.use {
                browser.start()
                val navigation = async(Dispatchers.IO) { runCatching { browser.navigate(site.url("/slow")) } }
                assertNotNull(withContext(Dispatchers.IO) { site.requests.poll(3, TimeUnit.SECONDS) })
                assertTrue(browser.setWindowVisible(true).visible)
                assertFalse(navigation.isCompleted)
                assertFalse(browser.dismissWindow().visible)
                assertTrue(browser.isRunning)
                browser.stopLoading()
                assertTrue(withTimeout(3000) { navigation.await() }.isFailure)
                assertTrue(browser.loadHtml("<title>停止后恢复</title>"))
                assertEquals("\"停止后恢复\"", browser.evaluateJs("document.title"))
                assertTrue(browser.setWindowVisible(true).visible)
            }
            assertEquals(0, browser.processExitCode)
        }
    }

    private fun bridge(): DesktopWebViewBridge {
        val executable = File(System.getProperty("kototoro.compat.bridge.exe"))
        assumeTrue(executable.isFile, "Windows WebView2 bridge must be built")
        val profile = Files.createDirectories(Path.of("build/test-wv2-profiles/window-${UUID.randomUUID()}"))
        return DesktopWebViewBridge(executable, profile)
    }
}
