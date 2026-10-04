@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.runtime

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry

class DesktopRepositoriesTest {
    @TempDir lateinit var directory: Path
    private val index = ExtensionStoreIndex("测试仓库", "FIX", "key", ExtensionStoreIndex.Contact("https://fixture.invalid"),
        ExtensionStoreIndex.ExtensionList(listOf(extension())))
    private fun extension(jar: String = "plugin.jar", pkg: String = "fixture.extension", apk: String = "plugin.apk") = ExtensionStoreIndex.Extension(
        "测试源", pkg, ExtensionStoreIndex.Resources(apk, "icon.png", jar), "1.6", 1, "1.6.1",
        ExtensionStoreIndex.ContentWarning.SAFE, listOf(ExtensionStoreIndex.Source(Long.MIN_VALUE, "中文源", "zh")))
    private fun encoded(value: ExtensionStoreIndex = index) = ProtoBuf.encodeToByteArray(ExtensionStoreIndex.serializer(), value)

    @Test
    fun `gzip protobuf relative external list persists and installs immutable owned JAR after restart`() = runBlocking<Unit> {
        val data = encoded(index.copy(extensionList = null, extensionListUrl = "lists/extensions.pb"))
        val list = ProtoBuf.encodeToByteArray(ExtensionStoreIndex.ExtensionList.serializer(),
            ExtensionStoreIndex.ExtensionList(listOf(extension("../plugin.jar"), extension("", "fixture.nopackage", ""))))
        val gzip = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(data) } }.toByteArray()
        serve(mapOf("/index.pb" to gzip, "/lists/extensions.pb" to list, "/plugin.jar" to jar())).use { server ->
            val root = directory.resolve("data")
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                DesktopRepositories(root, store.open("repos")).use { repos ->
                    val catalog = repos.fetch(server.url + "/index.pb")
                    assertEquals(2, catalog.extensions.size)
                    assertEquals(server.url + "/plugin.jar", catalog.extensions.first().resources.jarUrl)
                    assertEquals(server.url + "/lists/plugin.apk", catalog.extensions.first().resources.apkUrl)
                    val managed = repos.download(catalog, catalog.extensions.first())
                    assertTrue(managed.path.startsWith(root.resolve("extensions")))
                    assertEquals("fixture.extension", managed.identity.packageName)
                    val imported = repos.import(Files.write(directory.resolve("manual.jar"), jar()))
                    assertEquals(managed, imported)
                    assertEquals(1L, Files.list(root.resolve("extensions")).use { it.count() })
                    assertThrows(IllegalArgumentException::class.java) { runBlocking { repos.download(catalog, catalog.extensions.last()) } }
                }
            }
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                DesktopRepositories(root, store.open("repos")).use { repos ->
                    assertEquals("测试仓库", repos.saved().single().name)
                    assertEquals(server.url + "/index.pb", repos.saved().single().indexUrl)
                }
            }
        }
    }

    @Test
    fun `real Tsundoku APK from an APK-only repository entry is converted and classified as a novel extension`() = runBlocking<Unit> {
        val directoryName = System.getProperty("kototoro.dex.real.apks")
        org.junit.jupiter.api.Assumptions.assumeTrue(directoryName != null, "no -PrealApkDirectory")
        val apk = Path.of(directoryName).resolve("novelfull.apk")
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(apk), "novelfull.apk missing")
        val manifest = org.skepsun.kototoro.dex.ApkManifestReader.read(apk)
        val entry = ExtensionStoreIndex.Extension(
            "NovelFull", manifest.packageName, ExtensionStoreIndex.Resources("novelfull.apk", "icon.png", ""),
            manifest.versionName.substringBeforeLast('.'), manifest.versionCode, manifest.versionName,
            ExtensionStoreIndex.ContentWarning.SAFE, listOf(ExtensionStoreIndex.Source(1L, "NovelFull", "en")),
        )
        val data = encoded(index.copy(extensionList = ExtensionStoreIndex.ExtensionList(listOf(entry))))
        serve(mapOf("/index.pb" to data, "/novelfull.apk" to Files.readAllBytes(apk))).use { server ->
            val root = directory.resolve("data")
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                DesktopRepositories(root, store.open("repos")).use { repos ->
                    val catalog = repos.fetch(server.url + "/index.pb")
                    assertEquals("", catalog.extensions.single().resources.jarUrl)
                    val managed = repos.download(catalog, catalog.extensions.single())
                    assertEquals(manifest.packageName, managed.identity.packageName)
                    assertEquals(manifest.versionCode, managed.identity.versionCode)
                    // The managed artifact is the converted JVM jar, whatever the repository served.
                    assertFalse(org.skepsun.kototoro.dex.ApkExtensionConverter.isApk(managed.path))
                    val metadata = org.skepsun.kototoro.source.host.MihonJarInspector().inspect(managed.path)
                    assertEquals(SourceEcosystem.TSUNDOKU, metadata.ecosystem)
                    assertEquals(DesktopExtensionKind.MIHON, DesktopExtensionFiles.kind(managed.path))
                    assertEquals(DesktopExtensionKind.MIHON, DesktopExtensionFiles.kind(apk))
                }
            }
        }
    }
    @Test
    fun `a repository root without modern indexes falls back to the legacy array`() = runBlocking<Unit> {
        val legacy = """[{"name":"Aniyomi: 动画","pkg":"fixture.anime","apk":"anime-v14.1.apk","lang":"ja","code":1,
          "version":"14.1","nsfw":0,"sources":[{"name":"动画","lang":"ja","id":"9","baseUrl":"https://a.invalid"}]}]"""
        serve(mapOf("/owner/repo/index.min.json" to legacy.toByteArray())).use { server ->
            withRepositories { repos ->
                val catalog = repos.fetch(server.url + "/owner/repo")
                assertEquals("127.0.0.1/owner/repo", catalog.repository.name)
                val extension = catalog.extensions.single()
                assertEquals("14", extension.extensionLib)
                assertEquals(server.url + "/owner/repo/apk/anime-v14.1.apk", extension.resources.apkUrl)
                assertEquals("", extension.resources.jarUrl)
            }
        }
    }

    @Test
    fun `a legacy jar row without declared sources installs through the parser plugin pipeline`() = runBlocking<Unit> {
        val legacy = """[{"name":"Kototoro Parsers","pkg":"org.skepsun.kototoro.parsers",
          "version":"1.0.134","code":134,"apk":"plugin.jar","lang":"all","nsfw":0}]"""
        serve(mapOf(
            "/index.min.json" to legacy.toByteArray(),
            "/apk/plugin.jar" to parserJar(),
        )).use { server ->
            withRepositories { repos ->
                val catalog = repos.fetch(server.url + "/index.min.json")
                val extension = catalog.extensions.single()
                assertTrue(catalog.isParserPlugin(extension))
                assertEquals(server.url + "/apk/plugin.jar", extension.resources.apkUrl)
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { repos.download(catalog, extension) }
                }
                val managed = repos.downloadParserPlugin(catalog, extension)
                assertEquals("org.skepsun.kototoro.parsers", managed.id)
                assertEquals(DesktopExtensionKind.PARSER, DesktopExtensionFiles.kind(managed.path))
            }
        }
    }

    @Test
    fun `root falls back to modern JSON and preserves quoted long ids`() = runBlocking<Unit> {
        val json = """{"name":"JSON仓库","badgeLabel":"FIX","signingKey":"key","contact":{"website":"https://fixture.invalid"},
          "extensionList":{"extensions":[{"name":"源","packageName":"fixture.extension","extensionLib":"1.6",
          "versionCode":"9223372036854775807","versionName":"1.6.1","contentWarning":"CONTENT_WARNING_SAFE",
          "resources":{"apkUrl":"plugin.apk","iconUrl":"icon.png","jarUrl":"plugin.jar"},
          "sources":[{"id":"-9223372036854775808","name":"源","language":"zh"}]}]}}"""
        serve(mapOf("/index.json" to json.toByteArray())).use { server ->
            withRepositories { repos ->
                val catalog = repos.fetch(server.url)
                assertEquals(server.url + "/index.json", catalog.repository.indexUrl)
                assertEquals(Long.MAX_VALUE, catalog.extensions.single().versionCode)
                assertEquals(Long.MIN_VALUE, catalog.extensions.single().sources.single().id)
            }
        }
    }

    @Test
    fun `identity mismatch malformed index and changed signing key preserve previous saved repository`() = runBlocking<Unit> {
        serve(mapOf("/index.pb" to encoded(), "/bad.pb" to byteArrayOf(1, 2),
            "/changed.pb" to encoded(index.copy(signingKey = "different")), "/plugin.jar" to jar("fixture.other"))).use { server ->
            withRepositories { repos ->
                val catalog = repos.fetch(server.url + "/index.pb")
                assertThrows(IllegalArgumentException::class.java) { runBlocking { repos.download(catalog, catalog.extensions.single()) } }
                assertEquals(0L, Files.list(directory.resolve("data/extensions")).use { it.count() })
                assertThrows(Exception::class.java) { runBlocking { repos.fetch(server.url + "/bad.pb") } }
                assertEquals(listOf(catalog.repository), repos.saved())
                server.server.removeContext("/index.pb")
                server.server.createContext("/index.pb") { exchange ->
                    val changed = encoded(index.copy(signingKey = "different"))
                    exchange.sendResponseHeaders(200, changed.size.toLong()); exchange.responseBody.use { it.write(changed) }
                }
                assertThrows(IllegalArgumentException::class.java) { runBlocking { repos.fetch(server.url + "/index.pb") } }
                assertEquals(listOf(catalog.repository), repos.saved())
            }
        }
    }

    @Test
    fun `cancellation closes a streaming response and removes only the owned partial download`() = runBlocking<Unit> {
        serve(mapOf("/index.pb" to encoded())).use { server ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.server.createContext("/plugin.jar") { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(jar().take(32).toByteArray()); exchange.responseBody.flush()
                    started.countDown(); release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            withRepositories { repos ->
                val catalog = repos.fetch(server.url + "/index.pb")
                val operation = launch(Dispatchers.IO) { repos.download(catalog, catalog.extensions.single()) }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                try {
                    withTimeout(3_000) {
                        val artifacts = directory.resolve("data/extensions")
                        while (!Files.exists(artifacts) || Files.list(artifacts).use { it.count() } == 0L) delay(10)
                    }
                    withTimeout(3_000) { operation.cancelAndJoin() }
                } finally { release.countDown() }
                assertEquals(0L, Files.list(directory.resolve("data/extensions")).use { it.count() })
            }
        }
    }

    @Test
    fun `failed durable preference edit restores the old memory snapshot`() {
        val old = SourcePreferenceValue.Text("old")
        val memory = mutableMapOf<String, SourcePreferenceValue>("repositories" to old)
        val prefs = object : SourcePreferences {
            override fun snapshot() = memory.toMap()
            override fun edit(edit: SourcePreferenceEdit): Boolean {
                edit.changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                return false
            }
            override fun addListener(listener: SourcePreferenceListener) {}
            override fun removeListener(listener: SourcePreferenceListener) {}
        }
        assertThrows(java.io.IOException::class.java) { persistRepositoryPreference(prefs, "repositories", SourcePreferenceValue.Text("new")) }
        assertEquals(old, prefs.snapshot()["repositories"])
    }

    @Test
    fun `unsupported URLs and invalid JAR never publish managed artifacts`() = runBlocking<Unit> {
        withRepositories { repos ->
            for (url in listOf("file:///tmp/index.pb", "https://user:pass@fixture.invalid/index.pb", "https://fixture.invalid/index.pb#x")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { repos.fetch(url) } }
            }
            assertThrows(Exception::class.java) { runBlocking { repos.import(Files.writeString(directory.resolve("bad.apk"), "bad")) } }
            assertEquals(0L, Files.list(directory.resolve("data/extensions")).use { it.count() })
        }
    }

    private suspend fun withRepositories(block: suspend (DesktopRepositories) -> Unit) {
        val root = directory.resolve("data")
        FileSourcePreferenceStore(root.resolve("prefs")).use { store -> DesktopRepositories(root, store.open("repos")).use { block(it) } }
    }

    // Data-only archive for the inspector; actual extension construction is exercised by source-host/UI fixtures.
    private fun jar(pkg: String = "fixture.extension"): ByteArray = ByteArrayOutputStream().also { output ->
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("AndroidManifest.xml"))
            jar.write("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg"
              android:versionCode="1" android:versionName="1.6.1"><application android:label="源">
              <meta-data android:name="tachiyomix.extensionLib" android:value="1.6"/>
              <meta-data android:name="tachiyomi.extension.class" android:value=".Source"/>
              <meta-data android:name="tachiyomi.extension.nsfw" android:value="0"/>
              </application></manifest>""".toByteArray()); jar.closeEntry()
            jar.putNextEntry(JarEntry("${pkg.replace('.', '/')}/Source.class"))
            jar.write(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 52))
            jar.closeEntry()
        }
    }.toByteArray()

    private fun parserJar(): ByteArray = ByteArrayOutputStream().also { output ->
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("org/skepsun/kototoro/parsers/ContentParserFactoryKt.class"))
            jar.write(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 52))
            jar.closeEntry()
        }
    }.toByteArray()

    private fun serve(routes: Map<String, ByteArray>): FixtureServer {
        val executor = Executors.newCachedThreadPool { Thread(it, "repository-fixture").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        routes.forEach { (path, bytes) -> server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
        } }
        server.start()
        return FixtureServer(server, executor)
    }
    private class FixtureServer(val server: HttpServer, val executor: java.util.concurrent.ExecutorService) : java.io.Closeable {
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
