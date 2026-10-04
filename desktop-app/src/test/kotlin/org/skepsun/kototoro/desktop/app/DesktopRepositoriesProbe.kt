@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.SourceHostJar
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import org.skepsun.kototoro.source.host.MihonJarInspector
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** User-visible repository add/install/update followed by an offline process using only managed JARs. */
internal object DesktopRepositoriesProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val write = args[0].endsWith("write")
        val root = Path.of(args[1])
        val metadata = MihonJarInspector().inspect(Path.of(args[2]))
        val extension = ExtensionStoreIndex.Extension("离线测试扩展", metadata.packageName,
            ExtensionStoreIndex.Resources("plugin.apk", "icon.png", "plugin.jar"), metadata.extensionLib,
            metadata.versionCode, metadata.versionName, ExtensionStoreIndex.ContentWarning.SAFE,
            listOf(ExtensionStoreIndex.Source(9007199254740993L, "离线测试源", "zh")))
        val currentIndex = AtomicReference(ExtensionStoreIndex("测试扩展仓库", "FIX", "fixture-key",
            ExtensionStoreIndex.Contact("https://fixture.invalid"), ExtensionStoreIndex.ExtensionList(listOf(
                extension, extension.copy(packageName = "fixture.nopackage", name = "没有安装包", resources = extension.resources.copy(apkUrl = "", jarUrl = "")),
                extension.copy(packageName = "fixture.apkonly", name = "只有 APK", resources = extension.resources.copy(jarUrl = ""))))))
        val original = Files.readAllBytes(Path.of(args[2]))
        val currentJar = AtomicReference(original)
        val requests = AtomicInteger()
        val server = if (write) HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/index.pb") { exchange ->
                val bytes = ProtoBuf.encodeToByteArray(ExtensionStoreIndex.serializer(), currentIndex.get())
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/plugin.jar") { exchange ->
                requests.incrementAndGet()
                val bytes = currentJar.get()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            start()
        } else null
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1120, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(name: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(Path.of(args[3]).resolve("${args[0]}-$name.png"), it.bytes) }
                    }
                }
                idle()
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                if (write) {
                    val address = "http://127.0.0.1:${requireNotNull(server).address.port}/index.pb"
                    onNodeWithTag("repository-url").performTextInput(address)
                    onNodeWithTag("repository-add").performClick(); idle()
                    // APK-only entries are installable (converted on this machine); entries with no package are not.
                    for ((tag, installable) in listOf("fixture.apkonly" to true, "fixture.nopackage" to false)) {
                        onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-install:$tag"))
                        onNodeWithTag("extension-install:$tag").apply { if (installable) assertIsEnabled() else assertIsNotEnabled() }
                    }
                    onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-install:${metadata.packageName}"))
                    check(session.registry.installed().isEmpty())
                    snapshot("catalog")
                    onNodeWithTag("extension-install:${metadata.packageName}").performClick(); idle()
                    check(controller.state.value.sources.any { it.sourceId == 9007199254740993L })
                    onNodeWithText("已安装").assertExists()
                    val upgradedVersion = metadata.versionCode + 1
                    fun versioned(version: Long) = ByteArrayOutputStream().also { output ->
                        ZipOutputStream(output).use { zip -> ZipInputStream(original.inputStream()).use { input ->
                            while (true) {
                                val entry = input.nextEntry ?: break
                                zip.putNextEntry(ZipEntry(entry.name))
                                val bytes = input.readAllBytes()
                                zip.write(if (entry.name == "AndroidManifest.xml") bytes.toString(Charsets.UTF_8)
                                    .replace("android:versionCode=\"${metadata.versionCode}\"", "android:versionCode=\"$version\"")
                                    .toByteArray() else bytes)
                                zip.closeEntry()
                            }
                        } }
                    }.toByteArray()
                    currentJar.set(versioned(upgradedVersion))
                    currentIndex.set(currentIndex.get().copy(extensionList = ExtensionStoreIndex.ExtensionList(listOf(
                        extension.copy(versionCode = upgradedVersion)))))
                    onNodeWithTag("repository:$address").performClick(); idle()
                    onNodeWithText("更新").assertExists()
                    onNodeWithTag("extension-install:${metadata.packageName}").performClick(); idle()
                    check(session.registry.installed().single().metadata.versionCode == upgradedVersion)
                    check(requests.get() == 2)
                    // A newer version found by "检查更新" in the installed list; the superseded artifacts are deleted.
                    val thirdVersion = upgradedVersion + 1
                    currentJar.set(versioned(thirdVersion))
                    currentIndex.set(currentIndex.get().copy(extensionList = ExtensionStoreIndex.ExtensionList(listOf(
                        extension.copy(versionCode = thirdVersion)))))
                    onNodeWithTag("extension-check-updates").performClick(); idle()
                    check(controller.state.value.extensionUpdates.single().extension.versionCode == thirdVersion)
                    onNodeWithTag("extension-update:${metadata.packageName}").assertExists()
                    onNodeWithTag("extension-update-all").performClick(); idle()
                    check(session.registry.installed().single().metadata.versionCode == thirdVersion)
                    check(controller.state.value.extensionUpdates.isEmpty())
                    check(requests.get() == 3)
                    val jars = Files.list(root.resolve("extensions")).use { files -> files.filter { it.toString().endsWith(".jar") }.toList() }
                    check(jars.size == 1) { "superseded artifacts kept: $jars" }
                    onNodeWithTag("extension-auto-update").performClick(); idle()
                    check(controller.state.value.autoUpdateExtensions)
                    Files.writeString(root.resolve("expected-version.txt"), thirdVersion.toString())
                    snapshot("installed")
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:MIHON_9007199254740993"))
                    onNodeWithTag("source:MIHON_9007199254740993").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.items.isNotEmpty() || controller.state.value.error != null
                    }
                    idle()
                    check(controller.state.value.items.isNotEmpty())
                } else {
                    check(controller.state.value.repositories.single().name == "测试扩展仓库")
                    check(session.registry.installed().single().metadata.versionCode == Files.readString(root.resolve("expected-version.txt")).toLong())
                    check(controller.state.value.sources.isNotEmpty())
                    check(session.startupErrors.isEmpty())
                    // Automatic updates stay on; with the repository unreachable the start-up check fails quietly.
                    check(controller.state.value.autoUpdateExtensions)
                    snapshot("offline")
                }
                val recorded = session.storage.preferences.open("desktop_extensions").snapshot().getValue(metadata.packageName) as SourcePreferenceValue.Text
                val jar = SourceProtocolJson.decodeFromString<SourceHostJar>(recorded.value)
                check(Path.of(jar.path).startsWith(root.resolve("extensions")))
                check(Path.of(jar.path) != Path.of(args[2]))
                if (!write) {
                    // Uninstall from the installed list: sources, install record and artifact all go.
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                    onNodeWithTag("extension-uninstall:${metadata.packageName}").performClick(); idle()
                    onNodeWithTag("extension-uninstall-confirm").performClick(); idle()
                    check(session.registry.installed().isEmpty())
                    check(controller.state.value.sources.none { it.sourceId == 9007199254740993L })
                    check(metadata.packageName !in session.storage.preferences.open("desktop_extensions").snapshot())
                    check(!Files.exists(Path.of(jar.path))) { "artifact kept after uninstall" }
                }
            }
        } finally { runBlocking { controller.shutdown() }; server?.stop(0) }
        val moved = root.resolveSibling("closed-${args[0]}")
        Files.move(root, moved); Files.move(moved, root)
        println("DESKTOP_UI_OK=${args[0]}")
        println("REPOSITORY_JAR_DOWNLOADS=${requests.get()}")
        println("PRODUCTION_REQUESTS=0")
    }
}
