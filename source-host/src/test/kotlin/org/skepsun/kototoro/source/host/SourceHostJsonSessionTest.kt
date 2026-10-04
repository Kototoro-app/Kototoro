package org.skepsun.kototoro.source.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Public zero-argument SPI used only by the standalone CLI subprocess test. */
class CliFixturePlatform : SourceHostPlatform {
    override fun initialize(): ClassLoader {
        println("fixture runtime initialized")
        return javaClass.classLoader
    }
    override fun close() { println("fixture runtime closed") }
}

class SourceHostJsonSessionTest {
    @TempDir lateinit var directory: Path

    private class Platform(private val loader: URLClassLoader) : SourceHostPlatform {
        var initialized = 0
        var closed = 0
        override fun initialize(): ClassLoader { initialized++; return loader }
        override fun close() { closed++; loader.close() }
    }

    @Test
    fun `Windows session resolves Unicode space paths and executes persistent JSON image protocol`() = runBlocking {
        val root = Files.createDirectories(directory.resolve("调试 source space"))
        val fixture = RuntimeJarFixture(root)
        val jar = fixture.sourceJar()
        val config = SourceHostConfig("fixture", listOf(SourceHostJar(jar.fileName.toString(),
            fixture.jars.identity(jar))), "图片 cache")
        val platform = Platform(fixture.jars.compatibilityLoader())
        val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
        val page = SourcePage(Long.MIN_VALUE, "/unused", null, source,
            requestContext = SourcePageRequestContext(12, "https://fixture.invalid/原始?key=+",
                "https://fixture.invalid/image.png#解扰"))
        val calls = listOf(SourceCall.Sources, SourceCall.Describe(source.name),
            SourceCall.ListContent(source.name, 0, null, null), SourceCall.Image(page), SourceCall.Image(page))
        val lines = calls.mapIndexed { index, call ->
            SourceProtocolJson.encodeToString(SourceRequest(SOURCE_PROTOCOL_VERSION, "win-$index", call))
        }
        val output = StringWriter()
        SourceHostJsonSession.run(config, root, StringReader(lines.joinToString("\n")), output, platform)
        val replies = output.toString().lineSequence().filter(String::isNotBlank).map {
            SourceProtocolJson.decodeFromString<SourceResponse>(it)
        }.toList()
        assertEquals(5, replies.size)
        assertEquals((0..4).map { "win-$it" }, replies.map { it.requestId })
        assertTrue(replies.all { it.error == null })
        assertTrue((replies[1].result as SourceResult.Descriptor).descriptor.isImageFetchingSupported)
        assertEquals("popular 1", (replies[2].result as SourceResult.ListContent).content.single().title)
        val image = (replies[3].result as SourceResult.Image).artifact
        assertEquals(image, (replies[4].result as SourceResult.Image).artifact)
        assertArrayEquals(FileSourceImageStoreTest.png,
            Files.readAllBytes(root.resolve("图片 cache").resolve(image.relativePath)))
        assertEquals(1, platform.initialized)
        assertEquals(1, platform.closed)
        val encodedConfig = SourceProtocolJson.encodeToString(config)
        assertEquals(config, SourceProtocolJson.decodeFromString<SourceHostConfig>(encodedConfig))
    }

    @Test
    fun `malformed requests stay correlated and later requests continue until EOF`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val platform = Platform(fixture.jars.compatibilityLoader())
        val input = """{

            {"version":1,"requestId":"unsupported","call":{"operation":"describe","sourceName":"missing"}}
            {"version":1,"requestId":"last","call":{"operation":"sources"}}
        """.trimIndent()
        val output = StringWriter()
        SourceHostJsonSession.run(SourceHostConfig("fixture"), directory, StringReader(input), output, platform)
        val replies = output.toString().lineSequence().filter(String::isNotBlank).map {
            SourceProtocolJson.decodeFromString<SourceResponse>(it)
        }.toList()
        assertEquals(3, replies.size)
        assertEquals(SourceErrorCode.INVALID_REQUEST, replies[0].error?.code)
        assertEquals("unsupported", replies[1].requestId)
        assertEquals(SourceErrorCode.SOURCE_UNAVAILABLE, replies[1].error?.code)
        assertEquals("last", replies[2].requestId)
        assertEquals(emptyList<SourceRef>(), (replies[2].result as SourceResult.Sources).sources)
        assertEquals(1, platform.closed)
    }

    @Test
    fun `identity failure closes platform before accepting requests or creating image directory`() {
        val fixture = RuntimeJarFixture(directory)
        val jar = fixture.sourceJar()
        val platform = Platform(fixture.jars.compatibilityLoader())
        val config = SourceHostConfig("fixture", listOf(SourceHostJar(jar.toString(),
            fixture.jars.identity(jar).copy(sha256 = "0".repeat(64)))), "images")
        val output = StringWriter()
        assertThrows(SourceJarException::class.java) {
            runBlocking { SourceHostJsonSession.run(config, directory, StringReader("{}"), output, platform) }
        }
        assertEquals("", output.toString())
        assertFalse(Files.exists(directory.resolve("images")))
        assertEquals(1, platform.closed)
    }

    @Test
    fun `initialization failure still closes platform and input output remain caller owned`() {
        var closed = false
        val failed = object : SourceHostPlatform {
            override fun initialize(): ClassLoader = throw IllegalStateException("fixture init error")
            override fun close() { closed = true }
        }
        val input = StringReader("")
        val output = StringWriter()
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { SourceHostJsonSession.run(SourceHostConfig("fixture"), directory, input, output, failed) }
        }
        assertEquals("fixture init error", failure.message)
        assertTrue(closed)
        assertEquals(-1, input.read())
        output.write("caller can still write")
    }

    @Test
    fun `configured preferences reject unsupported provider and release it before creating storage`() {
        var closed = false
        val platform = object : SourceHostPlatform {
            override fun initialize(): ClassLoader = error("must not initialize")
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SourceHostJsonSession.run(SourceHostConfig("fixture", preferenceDirectory = "prefs"),
                directory, StringReader(""), StringWriter(), platform) }
        }
        assertTrue(closed)
        assertFalse(Files.exists(directory.resolve("prefs")))
    }

    @Test
    fun `preference initialization failure releases store after platform shutdown`() {
        var closed = false
        val platform = object : SourceHostPreferencePlatform {
            override fun initialize(): ClassLoader = error("must not initialize")
            override fun initialize(preferences: SourcePreferenceStore): ClassLoader = error("prefs init failure")
            override fun close() { closed = true }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SourceHostJsonSession.run(SourceHostConfig("fixture", preferenceDirectory = "prefs"),
                directory, StringReader(""), StringWriter(), platform) }
        }
        assertTrue(closed)
        FileSourcePreferenceStore(directory.resolve("prefs")).close()
    }

    @Test
    fun `standalone desktop CLI keeps bootstrap logs off stdout and closes platform at EOF`() {
        val config = directory.resolve("桌面 launch.json")
        val launchConfig = SourceHostConfig(CliFixturePlatform::class.java.name)
        Files.writeString(config, SourceProtocolJson.encodeToString(launchConfig))
        val classes = listOf(javaClass, SourceHostJsonSession::class.java, SourceRuntime::class.java,
            Dispatchers::class.java, Json::class.java, KSerializer::class.java, Unit::class.java)
        val classpath = classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        val executable = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(executable.toString(), "-cp", classpath,
            "org.skepsun.kototoro.source.host.SourceHostCliKt", "serve", config.toString()).start()
        try {
            process.outputStream.writer(Charsets.UTF_8).use {
                it.write("""{"version":1,"requestId":"desktop","call":{"operation":"sources"}}""" + "\n")
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI did not exit at EOF")
            val stdout = process.inputStream.reader(Charsets.UTF_8).readText()
            val stderr = process.errorStream.reader(Charsets.UTF_8).readText()
            assertEquals(0, process.exitValue(), stderr)
            val reply = SourceProtocolJson.decodeFromString<SourceResponse>(stdout.trim())
            assertEquals("desktop", reply.requestId)
            assertEquals(emptyList<SourceRef>(), (reply.result as SourceResult.Sources).sources)
            assertTrue(stderr.contains("fixture runtime initialized"))
            assertTrue(stderr.contains("fixture runtime closed"))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
