package org.skepsun.kototoro.desktop.compat

import android.app.Application
import android.os.Looper
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.*
import uy.kohesive.injekt.Injekt
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs in an isolated process because source SDK globals have one platform lifetime per JVM. */
object DesktopPlatformProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[1]).toAbsolutePath()
        val previousCookies = CookieHandler.getDefault()
        val previousInjekt = Injekt
        FileSourcePreferenceStore(root.resolve("preferences")).use { prefs ->
            if (args[0] == "bad-preferences") {
                val platform = MihonDesktopPlatform(root.resolve("compat"))
                val failure = runCatching {
                    platform.initialize(object : SourcePreferenceStore {
                        override fun open(namespace: String): SourcePreferences =
                            throw IllegalArgumentException("test backend unavailable")
                    })
                }.exceptionOrNull() ?: error("Startup should reject the failed preference backend")
                check(generateSequence(failure) { it.cause }.any { it.message == "test backend unavailable" })
                platform.close()
                check(GlobalContext.getOrNull() == null && Looper.getMainLooper() == null)
                check(Thread.getAllStackTraces().keys.none { it.name == "Kototoro compatibility main" && it.isAlive })
            } else if (args[0] == "bad-root") {
                val blocked = root.resolve("blocked")
                Files.writeString(blocked, "preserved")
                val platform = MihonDesktopPlatform(blocked)
                check(runCatching { platform.initialize(prefs) }.isFailure)
                platform.close()
                check(Files.readString(blocked) == "preserved")
                check(GlobalContext.getOrNull() == null && Looper.getMainLooper() == null)
            } else {
                val platform = MihonDesktopPlatform(root.resolve("compat"))
                val loader = platform.initialize(prefs)
                val app = platform.preferenceContext() as Application
                check(app.filesDir.toPath().startsWith(root.resolve("compat")))
                check(app.cacheDir.toPath().startsWith(root.resolve("compat")))
                check(!Files.exists(root.resolve("compat/server.conf")))
                val helper = Injekt.getInstance<NetworkHelper>(NetworkHelper::class.java)
                check(CookieHandler.getDefault() != null)
                if (args[0] == "lifecycle") {
                    check(runCatching { app.getSystemService("connectivity") }.exceptionOrNull()
                        is UnsupportedOperationException)
                    check(runCatching { app.resources }.exceptionOrNull() is UnsupportedOperationException)
                    check(runCatching { app.packageManager }.exceptionOrNull() is UnsupportedOperationException)
                    val callback = CountDownLatch(1)
                    val thread = AtomicReference<Thread>()
                    val settings = app.getSharedPreferences("callback", 0)
                    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        thread.set(Thread.currentThread()); callback.countDown()
                    }
                    settings.registerOnSharedPreferenceChangeListener(listener)
                    check(settings.edit().putString("setting", "字").commit())
                    check(callback.await(5, TimeUnit.SECONDS))
                    check(thread.get() === Looper.getMainLooper().thread)
                    settings.unregisterOnSharedPreferenceChangeListener(listener)
                    helper.cookieStore.add(URI("https://fixture.invalid"), HttpCookie("session", "private-fixture"))
                    check(prefs.open("cookie_store").snapshot().isNotEmpty())
                    check(runCatching { MihonDesktopPlatform(root.resolve("second")).initialize(prefs) }.isFailure)
                } else {
                    val jar = Path.of(args[2])
                    val metadata = MihonJarInspector().inspect(jar)
                    MihonJarRegistry(loader).use { registry ->
                        val extension = registry.load(jar, MihonJarIdentity(metadata.packageName,
                            metadata.versionCode, metadata.sha256))
                        check(extension.sources.single().displayName == if (args[0] == "read") "saved" else "initial")
                        val runtime = MihonSourceRuntime(registry, FileSourceImageStore(root.resolve("images")), app)
                        val client = SourceProtocolClient(SourceEndpoint(runtime)) { "desktop-platform" }
                        val source = "MIHON_9007199254740993"
                        check(client.describe(source).isPreferencesSupported)
                        val settings = client.getPreferences(source)
                        if (args[0] == "write") {
                            val rejected = client.updatePreference(source, settings.revision, settings.nodes[0].id,
                                SourcePreferenceValue.Text("rejected"))
                            check(rejected.status == SourcePreferenceUpdateStatus.REJECTED)
                            val saved = client.updatePreference(source, rejected.screen.revision, settings.nodes[0].id,
                                SourcePreferenceValue.Text("saved"))
                            check(saved.status == SourcePreferenceUpdateStatus.ACCEPTED)
                            val manga = client.getList(source, 0, null, null).single()
                            val details = client.getDetails(manga, SourceDetailsFetchMode.FORCE_REFRESH)
                            val pages = client.getPages(details.chapters!!.single(), null)
                            check(pages.single().requestContext!!.index == 12)
                            val image = client.fetchImage(pages.single())
                            val expected = Base64.getDecoder().decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8A" +
                                    "AwMCAO+aXuoAAAAASUVORK5CYII=",
                            )
                            check(Files.readAllBytes(root.resolve("images").resolve(image.relativePath))
                                .contentEquals(expected))
                        } else check(settings.nodes[0].value == SourcePreferenceValue.Text("saved"))
                    }
                }
                val dispatcher = helper.client.dispatcher.executorService
                platform.close(); platform.close()
                check(dispatcher.isShutdown)
                check(GlobalContext.getOrNull() == null && Looper.getMainLooper() == null)
                check(Thread.getAllStackTraces().keys.none { it.name == "Kototoro compatibility main" && it.isAlive })
                check(runCatching { platform.preferenceContext() }.isFailure)
            }
            check(CookieHandler.getDefault() === previousCookies)
            check(Injekt === previousInjekt)
        }
        val moved = root.resolveSibling("${root.fileName}-closed")
        Files.move(root, moved); Files.move(moved, root)
        println("DESKTOP_PLATFORM=PASS CASE=${args[0]} OS=${System.getProperty("os.name")} NETWORK_REQUESTS=0")
    }
}
