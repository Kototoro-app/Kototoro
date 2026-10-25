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
                // The installed JAR names its own source; the switch rows and the store key both use that name.
                fun installedSourceName() = controller.state.value.sources
                    .first { it.sourceId == 9007199254740993L }.source.name

                fun snapshot(name: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(Path.of(args[3]).resolve("${args[0]}-$name.png"), it.bytes) }
                    }
                }
                // A dialog is its own root, so the window capture above cannot be used while one is open.
                fun snapshotTag(tag: String, name: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        val file = Path.of(args[3]).resolve("${args[0]}-$name.png")
                        image.encodeToData()?.use { Files.write(file, it.bytes) }
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
                    // §178: a fresh package whose kind is still "ask every time" waits for Android's install question.
                    onNodeWithTag("install-prompt-dialog").assertExists()
                    // Answering without the checkbox leaves the kind asking; the sources stay on, as before §178.
                    onNodeWithTag("install-prompt-enable").performClick(); idle()
                    check(controller.state.value.extensionInstallPolicies["MIHON"] == null) {
                        "the dialog remembered an answer without the checkbox"
                    }
                    check(controller.state.value.sources.any { it.sourceId == 9007199254740993L })
                    onNodeWithText("已安装").assertExists()
                    // §181: Android's package header expands into the sources it declares; the label opens that
                    // source's own settings, the switch only enables or disables it.
                    val installedName = installedSourceName()
                    onNodeWithTag("installed-list").performScrollToNode(
                        hasTestTag("extension-sources:${metadata.packageName}"))
                    check(controller.state.value.installedEntries.single().sourceNames == listOf(installedName)) {
                        controller.state.value.installedEntries.single().sourceNames.toString()
                    }
                    onNodeWithTag("extension-source:$installedName").assertDoesNotExist()
                    onNodeWithTag("extension-sources:${metadata.packageName}").performClick(); idle()
                    // The expanded rows land below the fold of this page; a click only reaches a row that is on screen.
                    onNodeWithTag("installed-list").performScrollToNode(hasTestTag("extension-source:$installedName"))
                    onNodeWithTag("extension-source:$installedName").assertExists()
                    waitForIdle()
                    snapshot("installed-sources")
                    snapshotTag("installed-list", "installed-rows")
                    onNodeWithTag("extension-source:$installedName").performClick(); idle()
                    check(controller.state.value.disabledSources == setOf(installedName))
                    onNodeWithTag("extension-source:$installedName").performClick(); idle()
                    check(controller.state.value.disabledSources.isEmpty())
                    onNodeWithTag("installed-list").performScrollToNode(
                        hasTestTag("extension-source-open:$installedName"))
                    onNodeWithTag("extension-source-open:$installedName").performClick(); idle()
                    check(controller.state.value.screen == DesktopScreen.PREFERENCES)
                    check(controller.state.value.selectedSource?.source?.name == installedName)
                    check(controller.state.value.preferences?.source?.name == installedName)
                    snapshot("installed-sources-settings")
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:扩展与仓库").performClick(); idle()
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
                    // Android's per-source switch: search narrows the dialog's list, and a switched-off source leaves
                    // the rail (browsing, search, suggestions) without being uninstalled.
                    onNodeWithTag("source-switches-open").performClick(); idle()
                    onNodeWithTag("source-switch-dialog").assertExists()
                    val switchTag = "source-enabled:${installedSourceName()}"
                    onNodeWithTag("source-list-query").performTextInput("zzz-no-source"); idle()
                    onNodeWithText("没有匹配的来源").assertExists()
                    onNodeWithTag(switchTag).assertDoesNotExist()
                    onNodeWithTag("source-list-query").performTextClearance(); idle()
                    // Android's UnifiedEnabledFilter plus the "everything in this filter" pair: the confirmation
                    // counts the visible rows, and one write covers the whole batch.
                    onNodeWithTag("source-enabled-filter:DISABLED").performClick(); idle()
                    onNodeWithText("没有匹配的来源").assertExists()
                    onNodeWithTag("source-enabled-filter:ENABLED").performClick(); idle()
                    onNodeWithTag(switchTag).assertExists()
                    check(controller.state.value.disabledSources.isEmpty())
                    snapshotTag("source-switch-dialog", "filter-enabled")
                    onNodeWithTag("source-switches-disable-all").performClick(); idle()
                    onNodeWithTag("source-switch-batch-confirm").assertExists()
                    onNodeWithText("禁用当前筛选结果中的 1 个源？").assertExists()
                    snapshotTag("source-switch-batch-confirm", "batch-confirm")
                    onNodeWithTag("source-switch-batch-ok").performClick(); idle()
                    check(controller.state.value.disabledSources == setOf(installedSourceName()))
                    onNodeWithTag(switchTag).assertIsOff()
                    onNodeWithTag("source-enabled-filter:DISABLED").performClick(); idle()
                    onNodeWithTag(switchTag).assertExists()
                    onNodeWithTag("source-switches-enable-all").performClick(); idle()
                    onNodeWithText("启用当前筛选结果中的 1 个源？").assertExists()
                    onNodeWithTag("source-switch-batch-ok").performClick(); idle()
                    check(controller.state.value.disabledSources.isEmpty())
                    onNodeWithTag(switchTag).assertIsOn()
                    onNodeWithTag("source-enabled-filter:ALL").performClick(); idle()
                    // Android's selection bar (§192): 全选 enters selection mode and picks the visible rows, a tap on a
                    // row joins or leaves the selection, and the two action buttons write the whole selection at once.
                    onNodeWithTag("source-selection-all").performClick(); idle()
                    onNodeWithTag("source-selected:${installedSourceName()}").assertExists()
                    onNodeWithTag("source-selection-count").assertExists()
                    // Deselecting the only row ends the mode and writes nothing: the rows are switches again.
                    onNodeWithTag("source-selected:${installedSourceName()}").performClick(); idle()
                    onNodeWithTag("source-selected:${installedSourceName()}").assertDoesNotExist()
                    check(controller.state.value.disabledSources.isEmpty())
                    onNodeWithTag("source-selection-all").performClick(); idle()
                    snapshotTag("source-switch-dialog", "selection")
                    onNodeWithTag("source-selection-disable").performClick(); idle()
                    check(controller.state.value.disabledSources == setOf(installedSourceName()))
                    // The write ended the mode, and the filter followed the row to the side it moved to.
                    onNodeWithTag("source-selected:${installedSourceName()}").assertDoesNotExist()
                    onNodeWithTag(switchTag).assertIsOff()
                    onNodeWithTag("source-selection-all").performClick(); idle()
                    onNodeWithTag("source-selection-enable").performClick(); idle()
                    check(controller.state.value.disabledSources.isEmpty())
                    onNodeWithTag("source-enabled-filter:ALL").performClick(); idle()
                    onNodeWithTag("source-enable-list").performScrollToNode(hasTestTag(switchTag))
                    onNodeWithTag(switchTag).assertIsOn()
                    snapshotTag("source-switch-dialog", "sources")
                    onNodeWithTag(switchTag).performClick(); idle()
                    check(controller.state.value.disabledSources == setOf(installedSourceName()))
                    check(controller.state.value.enabledSources.none { it.sourceId == 9007199254740993L })
                    val disabledRecord = session.storage.preferences.open("desktop_extensions")
                        .snapshot()["disabled_sources"] as SourcePreferenceValue.TextSet
                    check(disabledRecord.values == setOf(installedSourceName()))
                    onNodeWithTag(switchTag).assertIsOff()
                    onNodeWithTag(switchTag).performClick(); idle()
                    check(controller.state.value.disabledSources.isEmpty())
                    onNodeWithTag("source-switch-done").performClick(); idle()
                    onNodeWithTag("source-switch-dialog").assertDoesNotExist()
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
                    // Left switched off on purpose: the rail drops it now,
                    // and the offline run proves the store kept it.
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                    onNodeWithTag("source-switches-open").performClick(); idle()
                    val offSwitchTag = "source-enabled:${installedSourceName()}"
                    onNodeWithTag("source-enable-list").performScrollToNode(hasTestTag(offSwitchTag))
                    onNodeWithTag(offSwitchTag).performClick(); idle()
                    check(controller.state.value.disabledSources == setOf(installedSourceName()))
                    snapshotTag("source-switch-dialog", "source-off")
                    onNodeWithTag("source-switch-done").performClick(); idle()
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source:MIHON_9007199254740993").assertDoesNotExist()
                    // The rail and the wide grid carry different empty hints; either one proves the source is gone.
                    onNodeWithText("安装或导入扩展后，内容源会显示在这里。").assertExists()
                } else {
                    check(controller.state.value.repositories.single().name == "测试扩展仓库")
                    check(session.registry.installed().single().metadata.versionCode == Files.readString(root.resolve("expected-version.txt")).toLong())
                    check(controller.state.value.sources.isNotEmpty())
                    check(session.startupErrors.isEmpty())
                    // Automatic updates stay on; with the repository unreachable the start-up check fails quietly.
                    check(controller.state.value.autoUpdateExtensions)
                    // §181: the package header still knows the sources it declares after an offline restart.
                    check(controller.state.value.installedEntries.single().sourceNames == listOf(installedSourceName()))
                    // The source switch survived the restart, and browsing still hides that source.
                    check(controller.state.value.disabledSources == setOf(installedSourceName()))
                    check(controller.state.value.enabledSources.none { it.sourceId == 9007199254740993L })
                    onNodeWithTag("source:MIHON_9007199254740993").assertDoesNotExist()
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
                    // The switch stayed off in the store, and the page opens already showing the disabled source.
                    onNodeWithTag("source-switches-open").performClick(); idle()
                    val offSwitchTag = "source-enabled:${installedSourceName()}"
                    onNodeWithTag("source-enable-list").performScrollToNode(hasTestTag(offSwitchTag))
                    onNodeWithTag(offSwitchTag).assertIsOff()
                    onNodeWithTag("source-switch-done").performClick(); idle()
                    onNodeWithTag("installed-list")
                        .performScrollToNode(hasTestTag("extension-uninstall:${metadata.packageName}"))
                    onNodeWithTag("extension-uninstall:${metadata.packageName}").performClick(); idle()
                    onNodeWithTag("extension-uninstall-confirm").performClick(); idle()
                    check(session.registry.installed().isEmpty())
                    check(controller.state.value.sources.none { it.sourceId == 9007199254740993L })
                    check(metadata.packageName !in session.storage.preferences.open("desktop_extensions").snapshot())
                    check(controller.state.value.disabledSources.isEmpty())
                    check(!Files.exists(Path.of(jar.path))) { "artifact kept after uninstall" }
                    // Forgetting the repository drops the index entry; no artifact is touched by it.
                    val removed = controller.state.value.repositories.single().indexUrl
                    onNodeWithTag("repository-remove:$removed").performClick(); idle()
                    check(controller.state.value.repositories.isEmpty())
                    check(session.repositories.saved().isEmpty())
                    check(controller.state.value.extensionCatalog == null)
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
