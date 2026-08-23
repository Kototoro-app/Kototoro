package org.skepsun.kototoro.tsundoku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import dagger.Lazy
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge

// Fake Tsundoku extension classes on the test classpath (loaded by name through the injected
// ClassLoader in the loader tests below — mirrors how the runtime resolves real APK classes).

class FxNovelSource : Source {
    override val id: Long = 9001L
    override val name: String = "fx-novel"
    override val isNovelSource: Boolean = true
}

class FxMangaSource : Source {
    override val id: Long = 9002L
    override val name: String = "fx-manga"
}

class FxNovelFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(FxNovelSource(), FxMangaSource())
}

class TsundokuExtensionLoaderTest {

    private val testClassLoader: ClassLoader = Thread.currentThread().contextClassLoader

    private fun novelPackageInfo(
        packageName: String = "eu.kanade.tachiyomi.novelextension.en.fixture",
        versionName: String = "1.4.1",
        sourceClass: String = FxNovelSource::class.java.name,
    ): PackageInfo {
        val metaData = mockk<Bundle>()
        every { metaData.containsKey("tachiyomi.novelextension.class") } returns true
        every { metaData.getString("tachiyomi.novelextension.class") } returns sourceClass
        every { metaData.containsKey("tachiyomi.novelextension.factory") } returns false
        every { metaData.getString("tachiyomi.novelextension.factory") } returns null
        every { metaData.containsKey("tachiyomix.contentWarning") } returns false
        every { metaData.containsKey("tachiyomix.extensionLib") } returns false
        every { metaData.getInt("tachiyomi.novelextension.nsfw", 0) } returns 0
        every { metaData.getString("tachiyomix.name") } returns null
        every { metaData.keySet() } returns setOf("tachiyomi.novelextension.class")
        val appInfo = ApplicationInfo().apply {
            this.metaData = metaData
            sourceDir = "/data/app/$packageName/base.apk"
        }
        return PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            this.applicationInfo = appInfo
            reqFeatures = arrayOf(FeatureInfo().apply { name = "tachiyomi.novelextension" })
        }
    }

    private fun withFeatures(pkg: PackageInfo, vararg names: String): PackageInfo {
        pkg.reqFeatures = names.map { FeatureInfo().apply { name = it } }.toTypedArray()
        return pkg
    }

    private fun packageManagerWith(vararg pkgs: PackageInfo): PackageManager {
        val pm = mockk<PackageManager>(relaxed = true)
        val list = pkgs.toList()
        every { pm.getInstalledPackages(any<Int>()) } returns list
        every { pm.getInstalledApplications(any<Int>()) } returns list.mapNotNull { it.applicationInfo }
        return pm
    }

    private fun mockContext(pm: PackageManager): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.classLoader } returns testClassLoader
        every { context.applicationContext } returns context
        every { context.filesDir } returns java.io.File("/data/user/0/test/files")
        every { context.getExternalFilesDir(any()) } returns java.io.File("/data/user/0/test/external")
        every { context.cacheDir } returns java.io.File("/data/user/0/test/cache")
        return context
    }

    private fun loader(context: Context): TsundokuExtensionLoader {
        val bridge = mockk<KotoInjektBridge>(relaxed = true)
        val lazy = mockk<Lazy<KotoInjektBridge>>(relaxed = true)
        every { lazy.get() } returns bridge
        // Off-device seam: no APK staging, classes resolved on the test classpath.
        val accessors = object : TsundokuApkAccessors {
            override fun prepareApkPath(context: Context, ecosystem: String, pkgName: String, sourcePath: String): String =
                sourcePath

            override fun createClassLoader(dexPath: String, nativeLibDir: String?, parent: ClassLoader): ClassLoader =
                testClassLoader
        }
        return TsundokuExtensionLoader(context, lazy, accessors)
    }

    private fun <T> runBlockingTest(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun `spec is the strict Tsundoku spec`() {
        val spec = TsundokuExtensionLoader.SPEC
        assertEquals("tsundoku", spec.ecosystemDir)
        assertEquals("TSUNDOKU_", spec.sourcePrefix)
        assertEquals("tachiyomi.novelextension", spec.requiredFeature)
        assertEquals(setOf("1.4", "1.6"), spec.acceptedLibVersions)
        assertTrue(spec.strictIdentification)
    }

    @Test
    fun `extract info from novel package maps to TsundokuExtensionInfo`() {
        val pkg = novelPackageInfo(
            packageName = "eu.kanade.tachiyomi.novelextension.en.fixture2",
            versionName = "1.6.2",
        )
        val pm = packageManagerWith(pkg)
        val context = mockContext(pm)

        val infos = loader(context).getInstalledExtensions(context)
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals("eu.kanade.tachiyomi.novelextension.en.fixture2", info.pkgName)
        assertEquals(1.6, info.libVersion)
        assertEquals("en", info.lang)
        assertNotNull(info.apkPath)
    }

    @Test
    fun `loadExtensions loads novel package and keeps legal sources but rejects manga object`() {
        val pkg = novelPackageInfo(
            packageName = "eu.kanade.tachiyomi.novelextension.en.fixture3",
            versionName = "1.4.1",
            sourceClass = FxNovelFactory::class.java.name,
        )
        val pm = packageManagerWith(pkg)
        val context = mockContext(pm)

        val results = runBlockingTest { loader(context).loadExtensions(context) }
        assertEquals(1, results.size)
        val success = results.single() as TsundokuLoadResult.Success
        assertEquals(listOf(9001L), success.sources.map { it.id })
        assertEquals(1, success.rejections.size)
        assertTrue(success.rejections.single().reason.contains("Not a novel source"))
    }

    @Test
    fun `loadExtensions rejects ambiguous novel+manga double-feature package`() {
        val pkg = withFeatures(
            novelPackageInfo(packageName = "eu.kanade.tachiyomi.novelextension.en.ambig"),
            "tachiyomi.novelextension",
            "tachiyomi.extension",
        )
        val pm = packageManagerWith(pkg)
        val context = mockContext(pm)

        val results = runBlockingTest { loader(context).loadExtensions(context) }
        assertEquals(1, results.size)
        val error = results.single() as TsundokuLoadResult.Error
        assertEquals("AMBIGUOUS", error.phase)
        assertTrue(error.message.contains("ambiguous"))
    }

    @Test
    fun `loadExtensions skips non-tsundoku packages`() {
        val mangaPkg = withFeatures(
            novelPackageInfo(packageName = "eu.kanade.tachiyomi.extension.en.manga"),
            "tachiyomi.extension",
        )
        val pm = packageManagerWith(mangaPkg)
        val context = mockContext(pm)

        val results = runBlockingTest { loader(context).loadExtensions(context) }
        assertEquals(0, results.size)
    }

    @Test
    fun `loadExtensions fails packages outside accepted lib versions`() {
        val pkg = novelPackageInfo(
            packageName = "eu.kanade.tachiyomi.novelextension.en.legacy",
            versionName = "1.5.9",
        )
        val pm = packageManagerWith(pkg)
        val context = mockContext(pm)

        val results = runBlockingTest { loader(context).loadExtensions(context) }
        assertEquals(1, results.size)
        val error = results.single() as TsundokuLoadResult.Error
        assertEquals("LIB_VERSION", error.phase)
        assertTrue(error.message.contains("Incompatible lib version"))
    }
}
