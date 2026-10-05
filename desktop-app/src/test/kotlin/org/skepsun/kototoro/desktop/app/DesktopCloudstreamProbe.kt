package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceEcosystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Cloudstream plugin (`.cs3`, d8 output like the official repositories publish) through the Windows UI: imported as
 * an extension, browsed (home sections, a section, search), opened, and an episode handed to the player with its
 * stream headers and subtitles. The write run installs it; the read run checks it is restored after a restart and
 * uninstalls it.
 */
internal object DesktopCloudstreamProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val fixture = Path.of(requireNotNull(System.getProperty("kototoro.desktop.cloudstream.fixture")))
        check(Files.isRegularFile(fixture)) { "fixture plugin missing: $fixture" }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 30_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                idle()
                if (args[0] == "cloudstream-write") {
                    runBlocking { controller.importJar(fixture).join() }
                    idle()
                }
                val listing = controller.state.value.sources.single { it.ecosystem == SourceEcosystem.CLOUDSTREAM }
                check(listing.displayName == "Fixture Stream" && listing.source.contentType == "VIDEO") { "$listing" }
                // Installed entries are listed when the extensions page opens; the session has them from the start.
                val entry = session.installedEntries().single { it.kind == DesktopInstalledKind.CLOUDSTREAM }
                check(entry.id == "FixtureRepo" && entry.sources == 1) { "$entry" }

                // The browse page lists it like every other source; opening it lists every home section.
                runBlocking { controller.explore().join() }; idle()
                onNodeWithTag("source:${listing.source.name}").performClick()
                waitUntil(timeoutMillis = 30_000) { controller.state.value.items.isNotEmpty() }; idle()
                check(controller.state.value.items.map { it.title } == listOf("热门 剧集 1", "最新 剧集 1")) {
                    "${controller.state.value.items.map { it.title }}"
                }
                onNodeWithTag("source-query").performTextReplacement("星际")
                onNodeWithText("搜索").performClick(); idle()
                check(controller.state.value.items.single().title == "电影 星际")

                controller.browseUnfiltered(); idle()
                val show = controller.state.value.items.first()
                runBlocking { controller.details(show).join() }; idle()
                val content = requireNotNull(controller.state.value.content)
                check(content.description == "剧集简介") { "${content.description}" }
                check(content.chapters!!.map { it.title } == listOf("第一集", "第二集"))
                check(DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO)

                // The episode reaches the player with the plugin's stream, its headers and subtitles.
                runBlocking { controller.read(content.chapters!!.first()).join() }; idle()
                check(controller.state.value.screen == DesktopScreen.VIDEO)
                val video = requireNotNull(controller.state.value.video)
                val stream = video.streams.single()
                check(stream.url == "https://fixture.invalid/streams/episode-1.m3u8") { stream.url }
                check(stream.headers.orEmpty()["X-Fixture"] == "episode-1") { "${stream.headers}" }
                check(stream.externalSubtitleTracks.single().url == "https://fixture.invalid/subtitles/episode-1.vtt")
                check(video.label(0).contains("1080p")) { video.label(0) }

                if (args[0] == "cloudstream-read") {
                    runBlocking { controller.uninstall(entry).join() }; idle()
                    check(controller.state.value.sources.none { it.ecosystem == SourceEcosystem.CLOUDSTREAM })
                    check(controller.state.value.installedEntries.none { it.kind == DesktopInstalledKind.CLOUDSTREAM })
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
