package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class MihonDesktopPlatformTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `real parent API initializes persists cookies dispatches listeners and closes Windows resources`() {
        probe("lifecycle", directory.resolve("中文 lifecycle space"))
    }

    @Test
    fun `real offline JAR content image and native settings survive independent desktop processes`() {
        val root = directory.resolve("中文 source space")
        probe("write", root)
        probe("read", root)
    }

    @Test
    fun `startup failure leaves original data and global state intact`() {
        probe("bad-root", directory.resolve("failed startup"))
    }

    @Test
    fun `failure after Koin and main loop initialization releases owned global state`() {
        probe("bad-preferences", directory.resolve("failed network initialization"))
    }

    @Test
    fun `production CLI reflects zero argument provider and preserves UTF8 JSON stdout until EOF`() {
        val root = Files.createDirectories(directory.resolve("中文 stdio space"))
        val jar = Path.of(System.getProperty("kototoro.compat.fixture.jar"))
        val metadata = MihonJarInspector().inspect(jar)
        val config = SourceHostConfig(MihonDesktopPlatform::class.java.name,
            listOf(SourceHostJar(jar.toString(), MihonJarIdentity(metadata.packageName,
                metadata.versionCode, metadata.sha256))), "images", "preferences")
        val configuration = root.resolve("config.json")
        Files.writeString(configuration, SourceProtocolJson.encodeToString(config), Charsets.UTF_8)
        val errors = root.resolve("stderr.log")
        val output = root.resolve("responses.jsonl")
        val process = ProcessBuilder(executable().toString(), "-cp",
            System.getProperty("kototoro.compat.test.classpath"),
            "org.skepsun.kototoro.source.host.SourceHostCliKt", "serve", configuration.toString())
            .redirectError(errors.toFile()).redirectOutput(output.toFile()).start()
        try {
            val source = "MIHON_9007199254740993"
            val calls = listOf(SourceCall.Sources, SourceCall.Describe(source), SourceCall.Preferences(source),
                SourceCall.ListContent(source, 0))
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { input ->
                calls.forEachIndexed { index, call ->
                    input.write(SourceProtocolJson.encodeToString(SourceRequest(1, "stdio-$index", call)))
                    input.newLine()
                }
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not close normally after EOF")
            assertEquals(0, process.exitValue(), Files.readString(errors))
            val responses = Files.readAllLines(output, Charsets.UTF_8).map {
                SourceProtocolJson.decodeFromString<SourceResponse>(it)
            }
            assertEquals(calls.size, responses.size)
            responses.forEachIndexed { index, response ->
                assertEquals("stdio-$index", response.requestId)
                assertNull(response.error)
            }
            assertEquals(source, (responses[0].result as SourceResult.Sources).sources.single().name)
            assertTrue((responses[1].result as SourceResult.Descriptor).descriptor.isPreferencesSupported)
            assertEquals("域名设置", (responses[2].result as SourceResult.Preferences).screen.nodes.single().title)
            assertEquals("offline 1", (responses[3].result as SourceResult.ListContent).content.single().title)
            assertTrue(Files.isDirectory(root.resolve("compat/files")))
            assertTrue(Files.isDirectory(root.resolve("compat/cache")))
            FileSourcePreferenceStore(root.resolve("preferences")).close()
            val moved = root.resolveSibling("stdio-closed")
            Files.move(root, moved); Files.move(moved, root)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun probe(mode: String, root: Path) {
        val log = directory.resolve("$mode-probe.log")
        val process = ProcessBuilder(executable().toString(), "-cp",
            System.getProperty("kototoro.compat.test.classpath"), DesktopPlatformProbe::class.java.name,
            mode, root.toString(), System.getProperty("kototoro.compat.fixture.jar"))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Desktop platform probe did not exit normally")
            val output = Files.readString(log, Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("DESKTOP_PLATFORM=PASS CASE=$mode"), output)
            assertTrue(output.contains("NETWORK_REQUESTS=0"), output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun executable() = Path.of(System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
}
