package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's endless browse list on Windows (the source page used to demand a manual 下一页):
 *
 * - the grid appends the next batch by itself as soon as its last rows are reached, without any button press;
 * - the source's empty batch ends the list: the rows already loaded stay, 没有更多了 appears and 下一批 disables;
 * - a search starts a fresh list from offset 0 and pages the same way.
 *
 * The fixture answers two works per requested offset and nothing from offset 4 on, so the list is four works long
 * (offsets 0 and 2) — small enough that the browser never has to scroll to reach the end.
 */
internal object DesktopBrowsePagingProbe {
    private val works = listOf(1000L, 1001L, 1002L, 1003L)

    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val fixtures = Path.of(args[9])
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(fixtures.resolve("fixture-kototoro.jar")).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
            // The controller side first: an appended batch keeps the rows already shown and advances the counter.
            val source = controller.state.value.sources.single { it.source.name == "FIXTURE_KOTOTORO_ONLY" }
            runBlocking { controller.selectSource(source).join() }
            check(controller.state.value.items.map { it.id } == works.take(2)) {
                controller.state.value.items.map { it.id }.toString()
            }
            runBlocking { controller.browseNextPage()?.join() }
            check(controller.state.value.items.map { it.id } == works && controller.state.value.offset == 1) {
                "${controller.state.value.items.map { it.id }} offset=${controller.state.value.offset}"
            }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.isNotEmpty() }
                waitForIdle()
                onNodeWithTag("content-grid").assertExists()
                // The window shows the two-batch list already; the grid's own footer asks for the batch past the end
                // as soon as it is composed, which is what ends the list here.
                waitUntil(timeoutMillis = 60_000) {
                    controller.state.value.browseExhausted || controller.state.value.error != null
                }
                idle()
                check(controller.state.value.error == null) {
                    "error=${controller.state.value.error} items=${controller.state.value.items.size} " +
                        "offset=${controller.state.value.offset}"
                }
                check(controller.state.value.items.map { it.id } == works) {
                    controller.state.value.items.map { it.id }.toString()
                }
                check(controller.state.value.offset == 1) { controller.state.value.offset.toString() }
                onNodeWithTag("browse-no-more").assertExists()
                onNodeWithTag("browse-next-page").assertIsNotEnabled()
                // Every appended row is a real grid item, not only a state field.
                onNodeWithTag("content:${works.last()}").assertExists()

                // A search starts a fresh list at offset 0 and pages the same way.
                onNodeWithTag("source-query").performTextReplacement("再次")
                onNodeWithText(desktopText("browse.search"), useUnmergedTree = true).performClick()
                idle()
                check(controller.state.value.items.first().title.contains("q=再次")) {
                    controller.state.value.items.first().title
                }
                waitUntil(timeoutMillis = 60_000) {
                    controller.state.value.browseExhausted && controller.state.value.items.size == works.size
                }
                check(controller.state.value.items.map { it.id } == works) {
                    controller.state.value.items.map { it.id }.toString()
                }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
