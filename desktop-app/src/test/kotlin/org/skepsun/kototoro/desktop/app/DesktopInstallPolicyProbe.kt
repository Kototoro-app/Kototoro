@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopRepository
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.source.host.MihonJarInspector
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's `extension_install_behavior` (§178). The write phase stores Android's `"<type>=<POLICY>"` text, imports a
 * plugin under "install only" and shows the sources it brought stay switched off, answers a repository install through
 * Android's confirm dialog with "remember", edits the settings sheet (per kind and the batch row) and snapshots both
 * dialogs; the read phase reopens the data root and checks the policy, the switches and the sheet's row all survived.
 */
internal object DesktopInstallPolicyProbe {
    fun run(args: Array<String>) {
        if (args[0] == "installpolicy-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val root = Path.of(args[1])
        val fixtures = Path.of(args[9])
        val mihonJar = Path.of(args[2])
        val kototoroJar = fixtures.resolve("fixture-kototoro.jar")
        val kotatsuJar = fixtures.resolve("fixture-kotatsu.jar")
        val metadata = MihonJarInspector().inspect(mihonJar)
        val extension = ExtensionStoreIndex.Extension("安装策略扩展", metadata.packageName,
            ExtensionStoreIndex.Resources("plugin.apk", "icon.png", "plugin.jar"), metadata.extensionLib,
            metadata.versionCode, metadata.versionName, ExtensionStoreIndex.ContentWarning.SAFE,
            listOf(ExtensionStoreIndex.Source(9007199254740993L, "安装策略源", "zh")))
        val index = ExtensionStoreIndex("安装策略仓库", "FIX", "fixture-key",
            ExtensionStoreIndex.Contact("https://fixture.invalid"), ExtensionStoreIndex.ExtensionList(listOf(extension)))
        val jarBytes = AtomicReference(Files.readAllBytes(mihonJar))
        val currentIndex = AtomicReference(index)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/index.pb") { exchange ->
                val bytes = ProtoBuf.encodeToByteArray(ExtensionStoreIndex.serializer(), currentIndex.get())
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/plugin.jar") { exchange ->
                val bytes = jarBytes.get()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            // Android leaves an unconfigured kind out of the set entirely: that is `ASK_EVERY_TIME`.
            check(session.extensionInstallPolicies().isEmpty()) { session.extensionInstallPolicies().toString() }
            check(controller.state.value.extensionInstallPolicies.isEmpty())
            val jarKind = kototoroJar.installKind()
            check(jarKind == DesktopInstallKind.JAR) { "the fixture jar keyed $jarKind" }
            // A repository entry's kind is its package name's runtime, the same marker the catalogue row's label uses.
            check(catalogKind(extension.copy(packageName = "eu.kanade.tachiyomi.animeextension.en.test")) == DesktopInstallKind.ANIYOMI)
            check(catalogKind(extension.copy(packageName = "eu.kanade.tachiyomi.novelextension.en.test")) == DesktopInstallKind.TSUNDOKU)
            check(catalogKind(extension) == DesktopInstallKind.MIHON) { "a plain managed jar is not Mihon" }
            check(AndroidStrings["source_type_jar"] == "JAR")
            check(AndroidStrings[ExtensionInstallPolicy.INSTALL_ONLY.labelKey()] == "仅安装") {
                AndroidStrings[ExtensionInstallPolicy.INSTALL_ONLY.labelKey()]
            }

            // Android stores the set as text; the default is dropped rather than kept as an explicit "ask" entry.
            runBlocking { controller.setExtensionInstallPolicy("JAR", ExtensionInstallPolicy.INSTALL_ONLY).join() }
            check(storedPolicies(session) == setOf("JAR=INSTALL_ONLY")) { storedPolicies(session).toString() }
            check(controller.state.value.extensionInstallPolicies[jarKind.typeName] == ExtensionInstallPolicy.INSTALL_ONLY)
            runBlocking { controller.setAllExtensionInstallPolicies(ExtensionInstallPolicy.INSTALL_AND_ENABLE).join() }
            check(storedPolicies(session).size == DesktopInstallKind.entries.size) { storedPolicies(session).toString() }
            runBlocking { controller.setAllExtensionInstallPolicies(ExtensionInstallPolicy.ASK_EVERY_TIME).join() }
            check(storedPolicies(session).isEmpty()) { "ASK_EVERY_TIME was stored: ${storedPolicies(session)}" }

            // "Install only" is a stored answer: the freshly imported plugin's source is installed but switched off.
            runBlocking { controller.setExtensionInstallPolicy("JAR", ExtensionInstallPolicy.INSTALL_ONLY).join() }
            runBlocking { controller.importJar(kototoroJar).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            check("FIXTURE_KOTOTORO_ONLY" in session.disabledSources()) { session.disabledSources().toString() }
            // "Install and enable" leaves them on, so a second plugin arrives switched on as before §178.
            runBlocking { controller.setExtensionInstallPolicy("JAR", ExtensionInstallPolicy.INSTALL_AND_ENABLE).join() }
            runBlocking { controller.importJar(kotatsuJar).join() }
            val enabledSource = controller.state.value.sources.single { it.source.name == "FIXTURE_SHARED" }
            check(enabledSource.source.name !in session.disabledSources()) { session.disabledSources().toString() }
            println("DESKTOP_INSTALL_POLICY=stored:${storedPolicies(session)} disabled:${session.disabledSources().sorted()}")

            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                // A dialog is its own root, so the window capture cannot be used while one is open.
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                        image.encodeToData()?.use { Files.write(output, it.bytes) }
                    }
                }
                // Mihon is still "ask every time", so the catalogue row installs nothing until the question is answered.
                idle()
                val beforeInstall = session.sourceListings(includeBroken = true).map { it.source.name }.toSet()
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                val address = "http://127.0.0.1:${server.address.port}/index.pb"
                onNodeWithTag("repository-url").performTextInput(address)
                onNodeWithTag("repository-add").performClick(); idle()
                onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-install:${metadata.packageName}"))
                onNodeWithTag("extension-install:${metadata.packageName}").performClick(); idle()
                onNodeWithTag("install-prompt-dialog").assertExists()
                snapshotTag("install-prompt-dialog", "prompt")
                check(controller.state.value.installPrompt?.kind == DesktopInstallKind.MIHON)
                // Cancel keeps everything as it was: no package, no stored policy.
                onNodeWithTag("install-prompt-cancel").performClick(); idle()
                check(controller.state.value.installPrompt == null)
                check(session.registry.installed().none { it.metadata.packageName == metadata.packageName })
                check(session.extensionInstallPolicies()["MIHON"] == null) { session.extensionInstallPolicies().toString() }
                onNodeWithTag("extension-install:${metadata.packageName}").performClick(); idle()
                onNodeWithTag("install-prompt-remember").performClick()
                onNodeWithTag("install-prompt-only").performClick(); idle()
                check(controller.state.value.installPrompt == null)
                check(session.registry.installed().any { it.metadata.packageName == metadata.packageName })
                check(session.extensionInstallPolicies()["MIHON"] == ExtensionInstallPolicy.INSTALL_ONLY) {
                    session.extensionInstallPolicies().toString()
                }
                val installed = session.sourceListings(includeBroken = true).map { it.source.name }.toSet() - beforeInstall
                check(installed.isNotEmpty()) { "the package brought no source" }
                check(installed.all { it in session.disabledSources() }) { session.disabledSources().toString() }
                check(controller.state.value.message?.isNotEmpty() == true)
                // A source settings row shows the mixed answer, and the sheet offers one row per kind plus the batch.
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick(); idle()
                onNodeWithTag("source-setting-install-behavior").performScrollTo().assertTextContains("仅安装", substring = true)
                onNodeWithTag("source-setting-install-behavior").performClick(); idle()
                onNodeWithTag("install-behavior-dialog").assertExists()
                onNodeWithTag("install-behavior-row:JAR").assertTextContains("安装并启用", substring = true)
                onNodeWithTag("install-behavior-row:MIHON").assertTextContains("仅安装", substring = true)
                onNodeWithTag("install-behavior-row:ANIYOMI").assertTextContains("每次询问", substring = true)
                snapshotTag("install-behavior-dialog", "sheet")
                // One kind's answer, stored under Android's own type name.
                onNodeWithTag("install-behavior-row:JAR").performClick(); idle()
                onNodeWithTag("install-behavior-choice").assertExists()
                // The kind is named in the title, so a click on the wrong row cannot pass for the right one.
                onNodeWithText("JAR · 扩展安装行为").assertExists()
                snapshotTag("install-behavior-choice", "choice")
                onNodeWithTag("install-behavior-option:INSTALL_ONLY").performClick(); idle()
                check(session.extensionInstallPolicies()["JAR"] == ExtensionInstallPolicy.INSTALL_ONLY) {
                    session.extensionInstallPolicies().toString()
                }
                // The batch row answers for every kind at once, and back to "ask" empties the stored set again.
                onNodeWithTag("install-behavior-batch").performClick(); idle()
                onNodeWithTag("install-behavior-option:INSTALL_AND_ENABLE").performClick(); idle()
                check(storedPolicies(session).size == DesktopInstallKind.entries.size) { storedPolicies(session).toString() }
                onNodeWithTag("install-behavior-row:TSUNDOKU").assertTextContains("安装并启用", substring = true)
                onNodeWithTag("install-behavior-batch").performClick(); idle()
                onNodeWithTag("install-behavior-option:ASK_EVERY_TIME").performClick(); idle()
                check(storedPolicies(session).isEmpty()) { "batch reset kept ${storedPolicies(session)}" }
                onNodeWithTag("install-behavior-row:JAR").performClick(); idle()
                onNodeWithTag("install-behavior-option:INSTALL_ONLY").performClick(); idle()
                onNodeWithTag("install-behavior-close").performClick(); idle()
                onNodeWithTag("install-behavior-dialog").assertDoesNotExist()
                // An update is not a fresh install: Android installs it without asking and the switches stay put.
                val upgraded = metadata.versionCode + 1
                jarBytes.set(versioned(mihonJar, metadata.versionCode, upgraded))
                currentIndex.set(currentIndex.get().copy(extensionList = ExtensionStoreIndex.ExtensionList(listOf(
                    extension.copy(versionCode = upgraded)))))
                check(controller.state.value.installedExtensions[metadata.packageName] == metadata.versionCode)
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                onNodeWithTag("repository:$address").performScrollTo().performClick(); idle()
                onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-install:${metadata.packageName}"))
                onNodeWithTag("extension-install:${metadata.packageName}").performClick(); idle()
                check(controller.state.value.installPrompt == null) { "an update asked the install question" }
                check(session.registry.installed().single().metadata.versionCode == upgraded)
                check(installed.all { it in session.disabledSources() })
                check(session.extensionInstallPolicies()["MIHON"] == null) { session.extensionInstallPolicies().toString() }
                println("DESKTOP_INSTALL_POLICY_UI=prompt:answered update:silent stored:${storedPolicies(session)}")
            }
        } finally {
            server.stop(0)
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            check(session.extensionInstallPolicies() == mapOf("JAR" to ExtensionInstallPolicy.INSTALL_ONLY)) {
                session.extensionInstallPolicies().toString()
            }
            check(controller.state.value.extensionInstallPolicies == session.extensionInstallPolicies())
            check("FIXTURE_KOTOTORO_ONLY" in session.disabledSources()) { session.disabledSources().toString() }
            check("FIXTURE_SHARED" !in session.disabledSources()) { session.disabledSources().toString() }
            check(storedPolicies(session) == setOf("JAR=INSTALL_ONLY")) { storedPolicies(session).toString() }
            println("DESKTOP_INSTALL_POLICY_PERSISTED=${storedPolicies(session)} disabled=${session.disabledSources().sorted()}")
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /** The policy text set as Android stores it, read back through the plain preference snapshot. */
    private fun storedPolicies(session: DesktopSession): Set<String> =
        (session.storage.preferences.open("desktop_extensions").snapshot()
            [EXTENSION_INSTALL_POLICIES_KEY] as? SourcePreferenceValue.TextSet)?.values.orEmpty()

    /** Which kind the catalogue would key a row as; a bare repository is enough because only the kind is asked. */
    private fun catalogKind(extension: ExtensionStoreIndex.Extension): DesktopInstallKind =
        installKind(DesktopRepositoryCatalog(
            DesktopRepository("https://fixture.invalid/index.pb", "安装策略仓库", "fixture-key"), listOf(extension),
        ), extension)

    /** A jar whose manifest claims [newVersion] instead of [oldVersion], so the repository offers it as an update. */
    private fun versioned(jar: Path, oldVersion: Long, newVersion: Long): ByteArray =
        java.util.zip.ZipInputStream(Files.newInputStream(jar)).use { input ->
            java.io.ByteArrayOutputStream().also { output ->
                java.util.zip.ZipOutputStream(output).use { zip ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        val bytes = input.readAllBytes()
                        zip.write(if (entry.name == "AndroidManifest.xml") {
                            bytes.toString(Charsets.UTF_8)
                                .replace("android:versionCode=\"$oldVersion\"", "android:versionCode=\"$newVersion\"")
                                .toByteArray()
                        } else {
                            bytes
                        })
                        zip.closeEntry()
                    }
                }
            }
        }.toByteArray()
}
