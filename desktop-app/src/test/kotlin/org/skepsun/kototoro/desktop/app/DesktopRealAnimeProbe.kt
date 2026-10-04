package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEcosystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A real Aniyomi APK (opt-in, `-PrealApkDirectory`) through the session: converted, installed, listed and read over the
 * live network down to the playable streams. A site being down is reported, not hidden.
 */
internal object DesktopRealAnimeProbe {
    fun run(args: Array<String>) {
        val directory = Path.of(requireNotNull(System.getProperty("kototoro.dex.real.apks")) { "no real APK directory" })
        val apk = listOfNotNull(System.getProperty("kototoro.real.anime"), "animeparadise.apk")
            .map(directory::resolve).first { Files.isRegularFile(it) }
        DesktopRealNovelProbe.verifyAllClasses(apk, Path.of(args[1]))
        runBlocking {
            val session = DesktopSession.open(Path.of(args[1]))
            try {
                val extension = session.importJar(apk)
                println("INSTALLED ${extension.label}: ${extension.sources.map { it.source.name }}")
                val listing = session.sourceListings().single { it.ecosystem == SourceEcosystem.ANIYOMI }
                check(listing.source.contentType.endsWith("VIDEO")) { "unexpected type ${listing.source.contentType}" }
                // Debugging aid: skip the JSON protocol, whose errors deliberately carry no cause or message.
                val client: org.skepsun.kototoro.core.source.SourceRuntime = if (System.getProperty("kototoro.real.direct") != null) {
                    org.skepsun.kototoro.source.host.AniyomiSourceRuntime(session.registry, session.storage.images)
                } else session.sources
                val describe = client.describe(listing.source.name)
                println("DESCRIBE latest=${listing.supportsLatest} sorts=${describe.sortOrders}")
                val page = withTimeout(90_000) { client.getList(listing.source.name, 0, null, null) }
                println("LIST ${page.size}: ${page.take(3).map { it.title }}")
                check(page.isNotEmpty()) { "empty listing" }
                val details = withTimeout(90_000) { client.getDetails(page.first(), SourceDetailsFetchMode.FORCE_REFRESH) }
                val episodes = requireNotNull(details.chapters) { "no episodes" }
                println("DETAILS ${details.title}: ${episodes.size} episodes, first=${episodes.first().title}")
                check(episodes.isNotEmpty())
                println("FIRST ${episodes.first().url} (of ${details.url})")
                val streams = withTimeout(120_000) { client.getPages(episodes.first(), null) }
                println("STREAMS ${streams.size}: ${streams.take(4).map { "${it.playbackLabel} ${it.url.take(80)}" }}")
                check(streams.isNotEmpty()) { "no playable stream" }
                println("DESKTOP_UI_OK=${args[0]}")
            } catch (error: Throwable) {
                Files.walk(Path.of(args[1])).use { files ->
                    files.filter { it.fileName.toString() == "source-errors.log" }
                        .forEach { println("DIAGNOSTICS ${it}\n" + Files.readString(it)) }
                }
                throw error
            } finally {
                session.close()
            }
        }
    }
}