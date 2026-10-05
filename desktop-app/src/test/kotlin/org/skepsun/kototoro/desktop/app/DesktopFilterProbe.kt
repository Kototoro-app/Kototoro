package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.MihonFilterRules
import org.skepsun.kototoro.core.source.SourceFilterValue
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path

/** Real cached native subclasses and opaque values are loaded only in an independent extension ClassLoader. */
internal object DesktopFilterProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runBlocking {
                controller.importJar(Path.of(args[8])).join()
                controller.importJar(Path.of(args[2])).join()
            }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = if (mode == "filters-narrow") 920 else 1260,
                height = if (mode == "filters-narrow") 620 else 900) {
                setContent { DesktopApp(controller) }
                fun settled(allowError: Boolean = false) {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    if (!allowError) check(controller.state.value.error == null) {
                        controller.state.value.error.orEmpty()
                    }
                }
                fun received(expected: String) {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.error == null &&
                            System.getProperty("fixture.filters.last") == expected
                    }
                    settled()
                }
                fun open() {
                    onNodeWithTag("source-filters").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.filterDialogOpen }
                    settled()
                }
                fun control(name: String, suffix: String = ""): SemanticsNodeInteraction {
                    val node = MihonFilterRules.flatten(controller.state.value.dynamicFilters!!.nodes)
                        .single { it.name == name }
                    return onNodeWithTag("filter:${node.id}$suffix")
                }
                fun choice(name: String, index: Int) {
                    control(name).performScrollTo().performClick()
                    control(name, ":option:$index").performClick()
                }
                fun edit() {
                    control("已完结").performScrollTo().performClick()
                    control("冒险").performScrollTo().performClick()
                    choice("版本", 1)
                    control("作者").performScrollTo().performTextReplacement("中文作者")
                    choice("排序", 1)
                    control("排序", ":direction").performScrollTo().performClick()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 2 }
                settled()
                onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:MIHON_9007199254740997"))
                onNodeWithTag("source:MIHON_9007199254740997").performClick()
                received("popular:1")
                onNodeWithTag("source-query").performTextReplacement("未应用查询")
                onNodeWithText("热门").performClick()
                received("popular:1")
                check(onNodeWithTag("source-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
                open()
                control("已完结").assertIsOn()
                control("冒险").assertTextEquals("包含")
                control("作者").performScrollTo().assertTextContains("默认作者")
                control("排序").performScrollTo().assertTextEquals("未选择")
                onNodeWithText("自定义控件：暂不支持此控件").performScrollTo().assertIsDisplayed()
                edit()
                val bitmap = onNodeWithTag("filter-dialog").captureToImage()
                Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use {
                        Files.write(Files.createDirectories(Path.of(args[3])).resolve("$mode-edited.png"), it.bytes)
                    }
                }
                onNodeWithTag("filter-cancel").performClick()
                settled()
                check(controller.state.value.appliedFilters.isEmpty())
                check(System.getProperty("fixture.filters.last") == "popular:1")
                onNodeWithTag("source-query").performTextReplacement("中文作品")
                open()
                control("已完结").assertIsOn()
                edit()
                onNodeWithTag("filter-apply").performClick()
                received("1|中文作品|false|2|alternate|中文作者|1:true")
                check(!controller.state.value.filterDialogOpen && controller.state.value.appliedFilters.size == 5)
                onNodeWithText("下一批").performClick()
                received("2|中文作品|false|2|alternate|中文作者|1:true")
                check(controller.state.value.offset == 1)
                onNodeWithText("上一批").performClick()
                received("1|中文作品|false|2|alternate|中文作者|1:true")
                onNodeWithTag("source-query").performTextReplacement("新查询")
                onNodeWithText("搜索", useUnmergedTree = true).performClick()
                received("1|新查询|false|2|alternate|中文作者|1:true")
                open()
                control("已完结").assertIsOff()
                control("冒险").assertTextEquals("排除")
                control("作者").performScrollTo().assertTextContains("中文作者")
                control("排序", ":clear").performScrollTo().performClick()
                onNodeWithTag("filter-apply").performClick()
                received("1|新查询|false|2|alternate|中文作者|null")
                onNodeWithText("最新").performClick()
                received("latest:1")
                check(controller.state.value.appliedFilters.isEmpty() && controller.state.value.query.isEmpty())
                check(onNodeWithTag("source-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
                onNodeWithText("下一批").performClick()
                received("latest:2")
                onNodeWithText("热门").performClick()
                received("popular:1")
                // A slow source keeps the window usable: navigation is enabled and replaces the pending request.
                System.setProperty("fixture.filters.slow", "true")
                onNodeWithText("最新").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.busy && System.getProperty("fixture.filters.last") == "latest:1"
                }
                waitForIdle()
                onNodeWithTag("nav:收藏").assertIsEnabled()
                onNodeWithText("热门").assertIsEnabled()
                val slowStarted = System.nanoTime()
                onNodeWithTag("nav:收藏").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                check(System.nanoTime() - slowStarted < 4_000_000_000L) { "Navigation waited for the slow source" }
                check(controller.state.value.screen == DesktopScreen.LIBRARY && controller.state.value.error == null) {
                    "${controller.state.value.screen} ${controller.state.value.error}"
                }
                System.clearProperty("fixture.filters.slow")
                onNodeWithTag("nav:浏览").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.screen == DesktopScreen.EXPLORE
                }
                settled()
                check(controller.state.value.browseOrder != "UPDATED") { "The cancelled request was committed" }
                onNodeWithText("热门").performClick()
                received("popular:1")
                open()
                edit()
                onNodeWithTag("filter-reset").performClick()
                control("已完结").performScrollTo().assertIsOn()
                control("冒险").assertTextEquals("包含")
                control("作者").performScrollTo().assertTextContains("默认作者")
                control("排序").performScrollTo().assertTextEquals("未选择")
                onNodeWithTag("filter-apply").performClick()
                received("1||true|1|original|默认作者|null")
                val before = controller.state.value
                open()
                edit()
                System.setProperty("fixture.filters.fail", "true")
                onNodeWithTag("filter-apply").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.error != null
                }
                settled(allowError = true)
                // The failure itself is shown, not only a pointer to the diagnostic log.
                check(controller.state.value.error.orEmpty().contains("Fixture filter failure")) {
                    controller.state.value.error.orEmpty()
                }
                onNodeWithTag("notice-error").assertTextContains("Fixture filter failure", substring = true)
                val failed = controller.state.value
                check(failed.filterDialogOpen && failed.items == before.items && failed.offset == before.offset &&
                    failed.appliedFilters == before.appliedFilters && failed.query == before.query)
                System.clearProperty("fixture.filters.fail")
                onNodeWithTag("filter-apply").performClick()
                received("1||false|2|alternate|中文作者|1:true")
                // Success and failure must both restore the extension's cached FilterList defaults.
                onNodeWithText("热门").performClick()
                received("popular:1")
                open()
                val definitions = MihonFilterRules.flatten(controller.state.value.dynamicFilters!!.nodes)
                check(definitions.single { it.name == "已完结" }.state == SourceFilterValue.Toggle(true))
                check(definitions.single { it.name == "排序" }.state == null)
                edit()
                onNodeWithTag("filter-apply").performClick()
                received("1||false|2|alternate|中文作者|1:true")
                onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:MIHON_9007199254740993"))
                onNodeWithTag("source:MIHON_9007199254740993").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.selectedSource?.sourceId == 9007199254740993L
                }
                settled()
                check(controller.state.value.dynamicFilters == null && controller.state.value.appliedFilters.isEmpty() &&
                    controller.state.value.query.isEmpty() && controller.state.value.browseOrder == null)
                onNodeWithText("最新").assertDoesNotExist()
                onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:MIHON_9007199254740997"))
                onNodeWithTag("source:MIHON_9007199254740997").performClick()
                received("popular:1")
                check(controller.state.value.appliedFilters.isEmpty())
            }
        } finally {
            System.clearProperty("fixture.filters.fail")
            System.clearProperty("fixture.filters.slow")
            runBlocking { controller.shutdown() }
        }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
