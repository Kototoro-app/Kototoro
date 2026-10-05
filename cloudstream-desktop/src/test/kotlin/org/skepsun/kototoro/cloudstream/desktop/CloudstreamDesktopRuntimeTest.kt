package org.skepsun.kototoro.cloudstream.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.source.host.SourceImageStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Cloudstream plugins in the Windows host's real environment: the Android compatibility runtime Mihon extensions use,
 * its shared HTTP client, the official library and the shared catalog. One platform per JVM (it owns globals).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudstreamDesktopRuntimeTest {
    private lateinit var root: Path
    private lateinit var preferences: FileSourcePreferenceStore
    private lateinit var platform: MihonDesktopPlatform
    private lateinit var registry: CloudstreamPluginRegistry
    private lateinit var runtime: CloudstreamSourceRuntime

    @BeforeAll
    fun start() {
        root = Files.createTempDirectory("cloudstream-desktop")
        preferences = FileSourcePreferenceStore(root.resolve("preferences"))
        platform = MihonDesktopPlatform(root.resolve("compat"))
        platform.initialize(preferences)
        registry = CloudstreamPluginRegistry(root.resolve("cloudstream"), platform.preferenceContext(), platform.sharedHttpClient())
        // Covers are not fetched here (the fixture's poster host does not exist).
        runtime = CloudstreamSourceRuntime(registry, SourceImageStore { _, _, _, _, _ -> error("unused") })
    }

    @AfterAll
    fun stop() {
        registry.close()
        platform.close()
        preferences.close()
    }

    @Test
    fun `a dexed plugin lists home sections searches loads episodes and resolves links with subtitles`() = runBlocking {
        val plugin = Path.of(System.getProperty("kototoro.cloudstream.fixture.cs3"))
        assumeTrue(Files.isRegularFile(plugin), "Android SDK with d8 is required to build the fixture plugin")
        val loaded = registry.load(plugin, "FixtureRepo", sha256(Files.readAllBytes(plugin)))
        assertEquals(listOf("Fixture Stream"), loaded.sources.map { it.displayName })
        val name = loaded.sources.single().name
        assertEquals("CLOUDSTREAM_FixtureRepo_Fixture Stream", name)
        assertEquals(listOf(name), runtime.getSources().map { it.name })
        val source = runtime.getSources().single()
        assertEquals("VIDEO", source.contentType)

        val descriptor = runtime.describe(name)
        assertTrue(descriptor.filterCapabilities.isSearchSupported)
        val sections = runtime.getFilterOptions(name).tagGroups.single()
        assertEquals(listOf("热门", "最新"), sections.tags.map { it.title })

        // A blank query aggregates every home section; a section tag narrows to one; page 2 is the last.
        assertEquals(listOf("热门 剧集 1", "最新 剧集 1"), runtime.getList(name, 0, null, null).map { it.title })
        val latest = sections.tags.last()
        assertEquals(listOf("最新 剧集 2"), runtime.getList(name, 1, null, SourceFilter(tags = setOf(latest))).map { it.title })
        assertTrue(runtime.getList(name, 2, null, SourceFilter(tags = setOf(latest))).isEmpty())

        val movie = runtime.getList(name, 0, null, SourceFilter(query = "星际")).single()
        assertEquals("电影 星际", movie.title)
        val movieDetails = runtime.getDetails(movie, SourceDetailsFetchMode.FORCE_REFRESH)
        assertEquals("测试电影", movieDetails.title)
        assertEquals(listOf("movie-data"), movieDetails.chapters!!.map { it.url })

        val show = runtime.getList(name, 0, null, null).first()
        val details = runtime.getDetails(show, SourceDetailsFetchMode.FORCE_REFRESH)
        assertEquals(show.id, details.id)
        assertEquals("剧集简介", details.description)
        assertEquals(setOf("剧情", "悬疑"), details.tags.map { it.title }.toSet())
        assertEquals(listOf("第一集", "第二集"), details.chapters!!.map { it.title })

        val streams = runtime.getPages(details.chapters!!.first(), null)
        val stream = streams.single()
        assertEquals("https://fixture.invalid/streams/episode-1.m3u8", stream.url)
        assertEquals(1080, stream.playbackQuality)
        assertEquals("episode-1", stream.headers!!["X-Fixture"])
        // ExtractorLink.getAllHeaders adds the referer under a lower-case name.
        assertEquals("https://fixture.invalid", stream.headers!!.entries.single { it.key.equals("Referer", true) }.value)
        assertTrue(stream.headers!!.containsKey("User-Agent"))
        assertEquals(listOf("https://fixture.invalid/subtitles/episode-1.vtt"), stream.externalSubtitleTracks.map { it.url })

        assertTrue(registry.unload("FixtureRepo"))
        assertFalse(registry.owns(name))
        assertTrue(runtime.getSources().isEmpty())
    }

    /** Official repository plugins (`-PcloudstreamPlugins=<dir of .cs3>`); with `-PcloudstreamOnline`, a live round trip. */
    @Test
    fun `real repository plugins load and register their providers`() = runBlocking {
        val directory = System.getProperty("kototoro.cloudstream.real")?.let(Path::of)
        assumeTrue(directory != null && Files.isDirectory(directory), "Pass -PcloudstreamPlugins=<directory of .cs3>")
        val plugins = Files.list(directory!!).use { files -> files.filter { it.toString().endsWith(".cs3") }.sorted().toList() }
        assumeTrue(plugins.isNotEmpty())
        for (file in plugins) {
            val id = file.fileName.toString().removeSuffix(".cs3")
            val loaded = registry.load(file, id, sha256(Files.readAllBytes(file)))
            println("cloudstream: $id -> ${loaded.sources.map { "${it.displayName} (${it.contentType}, ${it.locale})" }}")
            assertTrue(loaded.sources.isNotEmpty(), "$id registered no provider")
            loaded.sources.forEach { runtime.describe(it.name) }
        }
        if (System.getProperty("kototoro.cloudstream.online") == "true") {
            val archive = registry.sources().firstOrNull { it.displayName.contains("Archive", ignoreCase = true) }
            assumeTrue(archive != null, "InternetArchiveProvider.cs3 is needed for the online check")
            val found = runtime.getList(archive!!.name, 0, null, SourceFilter(query = "night of the living dead"))
            println("cloudstream online: ${found.size} results, first=${found.firstOrNull()?.title}")
            assertTrue(found.isNotEmpty())
            val details = runtime.getDetails(found.first(), SourceDetailsFetchMode.FORCE_REFRESH)
            println("cloudstream online: ${details.title} episodes=${details.chapters?.size}")
            val streams = runtime.getPages(details.chapters!!.first(), null)
            println("cloudstream online: ${streams.size} streams, first=${streams.firstOrNull()?.url}")
            assertTrue(streams.isNotEmpty())
        }
        plugins.forEach { registry.unload(it.fileName.toString().removeSuffix(".cs3")) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
