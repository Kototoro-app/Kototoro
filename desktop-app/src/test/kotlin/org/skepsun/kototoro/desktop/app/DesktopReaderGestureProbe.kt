package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import java.nio.file.Path

/**
 * The paged reader's Android-style interaction on Windows: neighbouring pages prefetch in the background so turns do
 * not wait, a mouse drag turns (or springs back below the threshold), Android's tap grid turns pages and toggles the
 * toolbar, the wheel turns pages that fit, and the page-turn animation setting persists.
 */
internal object DesktopReaderGestureProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-gestures-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            // Every chapter's first page has plain white margins around its content, for the crop check.
            val margined = root.resolveSibling("margined-page.png")
            java.awt.image.BufferedImage(400, 600, java.awt.image.BufferedImage.TYPE_INT_RGB).let { image ->
                val graphics = image.createGraphics()
                graphics.color = java.awt.Color.WHITE; graphics.fillRect(0, 0, 400, 600)
                graphics.color = java.awt.Color(0x3575B5); graphics.fillRect(40, 60, 320, 480)
                graphics.dispose()
                javax.imageio.ImageIO.write(image, "png", margined.toFile())
            }
            System.setProperty("fixture.reader.image.path", margined.toString())
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun at(index: Int) {
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.pageIndex == index && !controller.state.value.busy }
                    idle()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }; idle()
                onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }; idle()
                if (mode == "reader-gestures-read") {
                    check(controller.state.value.readerSettings.animation == ReaderAnimation.ADVANCED)
                    val grid = controller.state.value.readerSettings.tapGrid
                    check(grid[org.skepsun.kototoro.reader.domain.TapGridArea.CENTER_LEFT] ==
                        org.skepsun.kototoro.reader.ui.tapgrid.TapActions(
                            org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_NEXT, null)) { "Restored tap grid: $grid" }
                    check(grid[org.skepsun.kototoro.reader.domain.TapGridArea.BOTTOM_RIGHT]?.longTapAction ==
                        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_NEXT) { "Restored tap grid: $grid" }
                    check(grid[org.skepsun.kototoro.reader.domain.TapGridArea.CENTER] ==
                        org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig.defaults[
                            org.skepsun.kototoro.reader.domain.TapGridArea.CENTER]) { "Restored tap grid: $grid" }
                    println("DESKTOP_UI_OK=$mode")
                    return@runDesktopComposeUiTest
                }
                onNodeWithTag("preview-read").performClick()
                at(0)
                val pages = controller.state.value.pages

                // Prefetch: the next two pages load in the background without blocking the reader.
                waitUntil(timeoutMillis = 15_000) { pages.take(3).all { it.id in controller.state.value.readerImages } }
                waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching }
                val requests = System.getProperty("fixture.reader.requests").toInt()
                check(requests >= 3) { "Neighbouring pages were not prefetched: $requests" }
                val loaded = controller.state.value.readerImages.keys

                // A drag past the threshold turns forward (left-to-right: drag to the left), using the prefetched page.
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); press(); repeat(10) { moveBy(Offset(-50f, 0f)) }; release()
                }
                at(1)
                // A short drag springs back.
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); press(); moveBy(Offset(-40f, 0f)); release()
                }
                idle(); check(controller.state.value.pageIndex == 1)
                // Dragging the other way turns back.
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); press(); repeat(10) { moveBy(Offset(50f, 0f)) }; release()
                }
                at(0)
                // Turning moved the lookahead on (new pages may load), but no loaded page was fetched again.
                waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching }
                check(System.getProperty("fixture.reader.requests").toInt() - requests ==
                    (controller.state.value.readerImages.keys - loaded).size) { "A prefetched page was fetched again" }

                // Android's tap grid: right column forward, left column back, centre toggles the toolbar.
                val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("reader-viewport").performMouseInput { click(Offset(viewport.width * .9f, viewport.height / 2)) }
                at(1)
                onNodeWithTag("reader-viewport").performMouseInput { click(Offset(viewport.width * .1f, viewport.height / 2)) }
                at(0)
                onNodeWithTag("reader-viewport").performMouseInput { click(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-info-bar").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("reader-viewport").performMouseInput { click(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-back").fetchSemanticsNodes().isNotEmpty() }; idle()

                // The wheel turns a page that fits.
                onNodeWithTag("reader-viewport").performMouseInput { moveTo(center); scroll(1f) }
                at(1)

                // The page-turn animation is a persisted reader setting, chosen in Android's options panel (shared
                // core-ui sheet, Android's strings).
                fun closeSheet() {
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
                    idle()
                    check(onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isEmpty()) { "Sheet still open" }
                }
                onNodeWithTag("reader-options").performClick(); idle()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(AndroidStrings["reader_more_tab_layout"]).assertExists()
                onNodeWithText(AndroidStrings.array("reader_animation")[2]).performScrollTo().performClick(); idle()
                check(controller.state.value.readerSettings.animation == ReaderAnimation.ADVANCED)
                closeSheet()
                onNodeWithTag("reader-viewport").performMouseInput { click(Offset(viewport.width * .9f, viewport.height / 2)) }
                at(2)

                // Reader actions (the shared tap-grid config): the left-middle area is set to "next page" and the
                // bottom-right long tap (right click) to "next chapter"; both persist.
                // Android opens these from the panel's settings button; so does Windows.
                onNodeWithTag("reader-options").performClick(); idle()
                onNodeWithTag("reader-options-settings").performClick(); idle()
                onNodeWithTag("reader-panel").assertExists()
                onNodeWithTag("reader-option-tap-grid").performScrollTo()
                onNodeWithTag("reader-option-tap:CENTER_LEFT").performClick(); idle()
                onNodeWithTag("reader-option-tap-action:PAGE_NEXT").performScrollTo().performClick(); idle()
                onNodeWithTag("reader-option-tap-grid").performScrollTo()
                onNodeWithTag("reader-option-tap:BOTTOM_RIGHT").performMouseInput { rightClick(center) }; idle()
                onNodeWithTag("reader-option-tap-selector").assertTextEquals("右下 · 长按操作")
                onNodeWithTag("reader-option-tap-action:CHAPTER_NEXT").performScrollTo().performClick(); idle()
                controller.state.value.readerSettings.tapGrid.let { grid ->
                    check(grid[org.skepsun.kototoro.reader.domain.TapGridArea.CENTER_LEFT]?.tapAction ==
                        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_NEXT) { "Tap action not saved: $grid" }
                    check(grid[org.skepsun.kototoro.reader.domain.TapGridArea.BOTTOM_RIGHT]?.longTapAction ==
                        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_NEXT) { "Long-tap action not saved: $grid" }
                }
                onNodeWithTag("reader-panel-close").performClick(); idle()
                onNodeWithTag("reader-viewport").performMouseInput { click(Offset(viewport.width * .1f, viewport.height / 2)) }
                at(3)
                // A right click runs the long-tap action: the centre's default "show menu" opens the reader options.
                onNodeWithTag("reader-viewport").performMouseInput { rightClick(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isNotEmpty() }
                check(controller.state.value.pageIndex == 3) { "A right click must not turn the page" }
                closeSheet()
                runBlocking { controller.page(2).join() }; at(2)

                // Colour correction (the shared matrix) and the reader background reach the rendered pixels. Page 3 is a
                // wide yellow page, letterboxed at the top and bottom.
                fun pixel(x: Float, y: Float): java.awt.Color {
                    val bounds = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                    return onRoot().captureToImage().asSkiaBitmap().let { bitmap ->
                        java.awt.Color(bitmap.getColor((bounds.left + bounds.width * x).toInt(), (bounds.top + bounds.height * y).toInt()), true)
                    }
                }
                // Away from the white page numeral the fixture draws in the centre.
                val plain = pixel(.25f, .5f)
                check(plain.red > plain.blue + 100) { "Page 3 should render yellow: $plain" }
                val settings = controller.state.value.readerSettings
                runBlocking { controller.readerSettings(settings.copy(colorFilter = DesktopReaderColorFilter(inverted = true),
                    background = DesktopReaderBackground.WHITE, pageNumbers = true)).join() }; idle()
                waitUntil(timeoutMillis = 5_000) { pixel(.25f, .5f).let { it.blue > it.red + 100 } }
                // The shared chrome shades the upper margin; inspect the page background with it hidden.
                readerKey(androidx.compose.ui.input.key.Key.H)
                check(pixel(.5f, .03f).let { it.red > 250 && it.green > 250 && it.blue > 250 }) { "White background: ${pixel(.5f, .03f)}" }
                onNodeWithTag("reader-page-number").assertTextEquals("3 / 5")
                readerKey(androidx.compose.ui.input.key.Key.H)
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(
                    colorFilter = DesktopReaderColorFilter())).join() }; idle()

                // Vertical paging: a drag upwards and the down arrow turn forward.
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(vertical = true)).join() }
                idle()
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); press(); repeat(10) { moveBy(Offset(0f, -40f)) }; release()
                }
                at(3)
                onNodeWithTag("reader-viewport").requestFocus()
                onNodeWithTag("reader-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
                at(4)

                // Near the chapter's end the next chapter is prepared, so reading on opens it without waiting.
                val chapters = controller.state.value.content!!.chapters!!
                waitUntil(timeoutMillis = 15_000) { controller.preloadedChapterId == chapters[1].id }
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(automaticChapter = true)).join() }
                idle()
                onNodeWithTag("reader-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id == chapters[1].id }; idle()
                check(controller.preloadedChapterId == null)

                // Continuous mode: a mouse drag scrolls (and flings) the strip; the centre toggles the controls.
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(
                    mode = DesktopReaderMode.CONTINUOUS)).join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScrollReady && !controller.state.value.busy }
                val before = controller.state.value.pageIndex to controller.state.value.readerScroll
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); press(); repeat(12) { moveBy(Offset(0f, -40f), 16) }; release()
                }
                waitUntil(timeoutMillis = 15_000) {
                    val now = controller.state.value
                    now.readerScrollReady && (now.pageIndex to now.readerScroll).let {
                        it.first > before.first || it.first == before.first && it.second > before.second + 100f
                    }
                }
                onNodeWithTag("reader-viewport").performMouseInput { click(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-info-bar").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("reader-viewport").performMouseInput { click(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-back").fetchSemanticsNodes().isNotEmpty() }; idle()

                // Crop pages (the shared detector): the first page's white margins go, and it shows at its content's size.
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(
                    mode = DesktopReaderMode.SINGLE, vertical = false, cropPaged = true)).join() }; idle()
                runBlocking { controller.page(0).join() }; at(0)
                val first = controller.state.value.pages[0].id
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerImages[first]?.crop ==
                    org.skepsun.kototoro.reader.core.IntRect(40, 60, 360, 480 + 60) }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
                val shown = onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
                check(kotlin.math.abs(shown.width / shown.height - 320f / 480f) < .02f) { "Cropped page aspect: $shown" }
                check(pixel(.5f, .5f).let { it.blue > it.red + 60 })

                // Auto page turn: a page that fits turns by itself after the shared delay, and stops on request.
                runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(autoScrollSpeed = 1f)).join() }
                idle()
                onNodeWithTag("reader-autoscroll").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.pageIndex >= 1 }
                onNodeWithTag("reader-autoscroll").performClick(); idle()
                val stopped = controller.state.value.pageIndex
                Thread.sleep(2_500)
                check(controller.state.value.pageIndex == stopped) { "Auto page turn did not stop" }

                // Android's options panel drives the Windows settings: reading mode (icon bar), landscape double
                // pages, cover page, background swatch, colour correction and a quick action.
                fun settings() = controller.state.value.readerSettings
                onNodeWithTag("reader-options").performClick(); idle()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription(AndroidStrings["right_to_left"]).performClick(); idle()
                check(settings().rightToLeft && !settings().vertical && settings().mode != DesktopReaderMode.CONTINUOUS) {
                    "RTL: ${settings()} nodes=${onAllNodesWithContentDescription(AndroidStrings["right_to_left"]).fetchSemanticsNodes().map { it.boundsInRoot }} sheet=${onNodeWithTag("reader-panel-sheet").fetchSemanticsNode().boundsInRoot}"
                }
                onNodeWithTag("reader-options-double-page").performScrollTo().performClick(); idle()
                check(settings().mode == DesktopReaderMode.DOUBLE) { "Double pages: ${settings()}" }
                onNodeWithTag("reader-options-double-cover").performScrollTo().performClick(); idle()
                check(settings().doublePageCover)
                onNodeWithContentDescription(AndroidStrings["webtoon"]).performClick(); idle()
                check(settings().mode == DesktopReaderMode.CONTINUOUS)
                onNodeWithContentDescription(AndroidStrings["standard"]).performClick(); idle()
                check(settings().mode == DesktopReaderMode.SINGLE && !settings().rightToLeft) { "Standard: ${settings()}" }
                onNodeWithText(AndroidStrings["reader_more_tab_display"]).performClick(); idle()
                onNodeWithTag("reader-options-background:WHITE").performScrollTo().performClick(); idle()
                check(settings().background == DesktopReaderBackground.WHITE)
                onNodeWithTag("reader-color-invert").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { settings().colorFilter.inverted && !controller.state.value.busy }
                onNodeWithTag("reader-color-reset").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { settings().colorFilter.isEmpty && !controller.state.value.busy }
                // A quick action closes the sheet and runs: "add bookmark" toggles the current page's bookmark.
                val bookmarks = controller.state.value.bookmarks.size
                onNode(hasText(AndroidStrings["bookmark_add"]) and hasAnyAncestor(hasTestTag("reader-panel-sheet"))).performClick(); idle()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.bookmarks.size != bookmarks }
                check(onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isEmpty())
                println("DESKTOP_UI_OK=$mode")
            }
        } finally { runBlocking { controller.shutdown() }; System.clearProperty("fixture.reader.image.path") }
        println("NETWORK_REQUESTS=0")
    }
}
