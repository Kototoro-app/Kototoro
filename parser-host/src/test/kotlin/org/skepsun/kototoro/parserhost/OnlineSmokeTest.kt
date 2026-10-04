package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.source.host.FileSourceImageStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Opt-in check against a real site (`-Dkototoro.parserhost.online=true` on the test JVM, plus a raw kototoro-parsers
 * jar): browse, details, page list and one real image through the full runtime. Never part of the default run.
 */
class OnlineSmokeTest {
    @TempDir lateinit var root: Path

    @Test
    fun `MangaDex browses reads and downloads an image through the kototoro plugin`() {
        assumeTrue(System.getProperty("kototoro.parserhost.online") == "true", "Online smoke tests are opt-in")
        val jar = listOfNotNull(System.getProperty("kototoro.parserhost.real.kototoro"),
            "../../kototoro-parsers/build/libs/kototoro-parsers-1.0.jar").map(Path::of).firstOrNull(Files::isRegularFile)
        assumeTrue(jar != null, "No raw kototoro-parsers jar available")
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        TestPlatform(root, client).use { platform ->
            ParserPluginRegistry(platform).use { registry ->
                registry.load(jar!!, "kototoro-parsers")
                val runtime = ParserSourceRuntime(registry, platform, FileSourceImageStore(root.resolve("images")))
                runBlocking {
                    val items = runtime.getList("MANGADEX", 0, null, null)
                    println("MANGADEX list: ${items.size} items, first=${items.firstOrNull()?.title}")
                    assertTrue(items.isNotEmpty())
                    val details = runtime.getDetails(items.first(), SourceDetailsFetchMode.FORCE_REFRESH)
                    val chapters = details.chapters.orEmpty()
                    println("details: ${details.title}, ${chapters.size} chapters, cover=${details.coverUrl}")
                    assertTrue(chapters.isNotEmpty())
                    val cover = runtime.fetchCover(details, large = false)
                    println("cover: ${cover.contentType} ${cover.byteSize} bytes")
                    val pages = runtime.getPages(chapters.last(), null)
                    println("pages: ${pages.size}")
                    assertTrue(pages.isNotEmpty())
                    val image = runtime.fetchImage(pages.first())
                    println("page image: ${image.contentType} ${image.byteSize} bytes")
                    assertTrue(image.byteSize > 1000)
                }
            }
        }
    }
}
