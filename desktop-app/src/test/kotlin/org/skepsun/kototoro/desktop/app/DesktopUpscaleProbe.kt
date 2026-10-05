package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleTool
import java.nio.file.Path

/**
 * Reader page super-resolution with the official ncnn-vulkan programs (opt-in: `-PncnnDirectory` with the two release
 * zips; needs a Vulkan GPU). Choosing a model before its program exists keeps the original page and says why;
 * installing it upgrades the open page; the setting survives as a reader preference and turning it off restores
 * the original.
 */
internal object DesktopUpscaleProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val archives = Path.of(requireNotNull(System.getProperty("kototoro.ncnn.dir")) { "no ncnn directory" })
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun settled(allowError: Boolean = false) {
                    waitUntil(timeoutMillis = 60_000) { !controller.state.value.busy }
                    waitForIdle()
                    if (!allowError) check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun width() = controller.state.value.readerImages.getValue(
                    controller.state.value.pages[controller.state.value.pageIndex].id).width
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                settled()
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                settled()
                onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settled()
                onNodeWithTag("preview-read").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER && controller.state.value.image != null }
                settled()
                val original = width()

                // Super-resolution models are in the settings opened from Android's options panel.
                onNodeWithTag("reader-options").performClick()
                waitForIdle()
                onNodeWithTag("reader-options-settings").performClick()
                waitForIdle()
                onNodeWithTag("reader-option-upscale:REALESR_ANIMEVIDEO_2X").performScrollTo().performClick()
                settled()
                // No program yet: the page stays as it was and the panel offers the installation.
                check(width() == original) { "page changed without a program" }
                check(session.upscaleError.value?.contains("尚未安装") == true) { session.upscaleError.value.orEmpty() }
                onNodeWithTag("reader-upscaler-install").performScrollTo().assertExists()
                runBlocking { controller.installUpscalerArchive(DesktopUpscaleTool.REALESRGAN,
                    archives.resolve(DesktopUpscaleTool.REALESRGAN.asset)).join() }
                settled()
                check(width() == original * 2) { "after install: ${width()} vs $original" }
                check(controller.state.value.image.toString().contains("upscale")) { controller.state.value.image.toString() }

                onNodeWithTag("reader-option-upscale:REALCUGAN_2X").performScrollTo().performClick()
                settled()
                runBlocking { controller.installUpscalerArchive(DesktopUpscaleTool.REALCUGAN,
                    archives.resolve(DesktopUpscaleTool.REALCUGAN.asset)).join() }
                settled()
                onNodeWithTag("reader-option-noise:2").performScrollTo().performClick()
                settled()
                check(width() == original * 2)
                check(controller.state.value.readerSettings.upscale.let { it.model == DesktopUpscaleModel.REALCUGAN_2X && it.noise == 2 })

                onNodeWithTag("reader-option-upscale:OFF").performScrollTo().performClick()
                settled()
                check(width() == original) { "turning it off keeps ${width()}" }
                onNodeWithTag("reader-option-upscale:REALESRGAN_4X_ANIME").performScrollTo().performClick()
                settled()
                check(width() == original * 4) { "4x: ${width()}" }
                // Android's Anime4K page modes run offscreen through libmpv when it is present.
                if (org.skepsun.kototoro.desktop.player.MpvLocator.find(Path.of(args[1])) != null) {
                    onNodeWithTag("reader-option-upscale:ANIME4K_B").performScrollTo().performClick()
                    settled()
                    check(width() == original * 2) { "Anime4K B: ${width()}" }
                    check(session.upscaleError.value == null) { session.upscaleError.value.orEmpty() }
                    onNodeWithTag("reader-option-upscale:ANIME4K_C").performScrollTo().performClick()
                    settled()
                    check(width() == original) { "Anime4K C keeps the size: ${width()}" }
                    check(controller.state.value.image.toString().contains("upscale"))
                    println("ANIME4K_PAGES ok")
                }
                onNodeWithTag("reader-option-upscale:REALESRGAN_4X_ANIME").performScrollTo().performClick()
                settled()            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        // The choice is a reader preference: what the next start restores from (one platform per JVM, so read it).
        runBlocking {
            org.skepsun.kototoro.desktop.runtime.DesktopRuntime.open(Path.of(args[1])).use { runtime ->
                val saved = runtime.preferences.open("desktop_reader").snapshot()
                check((saved["upscale_model"] as? org.skepsun.kototoro.core.source.SourcePreferenceValue.Text)?.value ==
                    DesktopUpscaleModel.REALESRGAN_4X_ANIME.name) { saved.toString() }
            }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
