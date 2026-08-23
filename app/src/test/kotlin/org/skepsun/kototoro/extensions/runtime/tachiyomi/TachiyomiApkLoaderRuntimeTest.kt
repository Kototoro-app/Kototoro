package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ---- Fake extension classes on the test classpath, loaded by name through an injected
// ---- ClassLoader so no APK/dex is needed (mirrors how the runtime resolves real APK classes).

class FakeNovelSourceA : Source {
    override val id: Long = 101L
    override val name: String = "fake-novel-a"
    override fun isNovelSource(): Boolean = true
}

class FakeNovelSourceB : Source {
    override val id: Long = 101L // deliberately duplicates A's id
    override val name: String = "fake-novel-b"
    override fun isNovelSource(): Boolean = true
}

class FakeMangaSource : Source {
    override val id: Long = 102L
    override val name: String = "fake-manga"
}

class FakeNovelFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(FakeNovelSourceA(), FakeMangaSource())
}

class ThrowingNovelFactory : SourceFactory {
    override fun createSources(): List<Source> = throw RuntimeException("factory boom")
}

class NotASourceInstance

class NoDefaultCtorSource : Source {
    constructor(id: Long) : super()
    override val id: Long = 103L
    override val name: String = "no-default-ctor"
}

class TachiyomiApkLoaderRuntimeTest {

    private val testClassLoader: ClassLoader = Thread.currentThread().contextClassLoader

    private fun pkgInfo(
        packageName: String = "eu.kanade.tachiyomi.novelextension.en.faketest",
        versionName: String,
        sourceClass: String?,
        factoryClass: String? = null,
        nsfw: Int = 0,
        extensionLib: Any? = null,
    ): PackageInfo {
        val metaData = mockk<Bundle>()
        every { metaData.containsKey("tachiyomi.novelextension.class") } returns (sourceClass != null)
        every { metaData.getString("tachiyomi.novelextension.class") } returns sourceClass
        every { metaData.containsKey("tachiyomi.novelextension.factory") } returns (factoryClass != null)
        every { metaData.getString("tachiyomi.novelextension.factory") } returns factoryClass
        every { metaData.containsKey("tachiyomix.contentWarning") } returns false
        every { metaData.containsKey("tachiyomix.extensionLib") } returns (extensionLib != null)
        every { metaData.get("tachiyomix.extensionLib") } returns extensionLib
        every { metaData.getInt("tachiyomi.novelextension.nsfw", 0) } returns nsfw
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
            reqFeatures = emptyArray()
        }
    }

    private fun info(pkg: PackageInfo): TachiyomiApkLoaderRuntime.TachiyomiApkInfo {
        val result = TachiyomiApkLoaderRuntime.extractExtensionInfo(
            spec = TachiyomiApkEcosystemSpecs.TSUNDOKU,
            pkgInfo = pkg,
            getAppLabel = { "Label" },
        )
        return (result as TachiyomiApkLoaderRuntime.InfoResult.Ok).info
    }

    // ---- extractExtensionInfo ----

    @Test
    fun `extracts info from valid metadata with direct source class`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = "org.skepsun.kototoro.extensions.runtime.tachiyomi.FakeNovelSourceA")
        val i = info(pkg)
        assertEquals("eu.kanade.tachiyomi.novelextension.en.faketest", i.packageName)
        assertEquals("1.4", i.libVersion)
        assertEquals("en", i.lang)
        assertEquals("1.4.1", i.versionName)
        assertNotNull(i.apkPath)
    }

    @Test
    fun `lib version override wins over versionName`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = "x.y.Source", extensionLib = 1.6)
        assertEquals("1.6", info(pkg).libVersion)
    }

    @Test
    fun `nsfw metadata maps to isNsfw`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = "x.y.Source", nsfw = 1)
        assertTrue(info(pkg).isNsfw)
    }

    @Test
    fun `extract reports no application info`() {
        val pkg = PackageInfo().apply { packageName = "a.b"; versionName = "1.0" }
        val result = TachiyomiApkLoaderRuntime.extractExtensionInfo(
            TachiyomiApkEcosystemSpecs.TSUNDOKU, pkg, { "x" },
        )
        assertTrue(result is TachiyomiApkLoaderRuntime.InfoResult.Err)
        assertEquals("No ApplicationInfo", (result as TachiyomiApkLoaderRuntime.InfoResult.Err).error.message)
    }

    @Test
    fun `extract reports missing source class in manifest`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = null)
        val result = TachiyomiApkLoaderRuntime.extractExtensionInfo(
            TachiyomiApkEcosystemSpecs.TSUNDOKU, pkg, { "x" },
        )
        assertTrue(result is TachiyomiApkLoaderRuntime.InfoResult.Err)
        assertTrue((result as TachiyomiApkLoaderRuntime.InfoResult.Err).error.message.contains("No source class"))
    }

    // ---- loadFromClass (instantiation + validation) ----

    @Test
    fun `loads a direct novel source`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = FakeNovelSourceA::class.java.name)
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { null }
        val success = result as TachiyomiLoadResult.Success
        assertEquals(1, success.sources.size)
        assertEquals(101L, success.sources.first().id)
        assertEquals(0, success.rejections.size)
    }

    @Test
    fun `factory sources are filtered per source - manga object rejected while novel kept`() {
        val pkg = pkgInfo(versionName = "1.4.2", sourceClass = FakeNovelFactory::class.java.name)
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { source ->
            if (source.isNovelSource()) null else "Not a novel source (isNovelSource=false)"
        }
        val success = result as TachiyomiLoadResult.Success
        assertEquals(listOf(101L), success.sources.map { it.id })
        assertEquals(1, success.rejections.size)
        assertTrue(success.rejections.single().reason.contains("Not a novel source"))
    }

    @Test
    fun `duplicate source ids are rejected per source`() {
        val pkg = pkgInfo(
            versionName = "1.4.1",
            sourceClass = listOf(FakeNovelSourceA::class.java.name, FakeNovelSourceB::class.java.name).joinToString(";"),
        )
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { null }
        val success = result as TachiyomiLoadResult.Success
        assertEquals(1, success.sources.size)
        assertEquals(1, success.rejections.size)
        assertTrue(success.rejections.single().reason.contains("Duplicate source ID"))
    }

    @Test
    fun `a throwing factory fails the whole package`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = ThrowingNovelFactory::class.java.name)
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { null }
        val error = result as TachiyomiLoadResult.Error
        assertEquals(TachiyomiLoadErrorPhase.INSTANTIATION, error.phase)
        assertTrue(error.message.contains("factory boom"))
    }

    @Test
    fun `a class without default constructor fails the package`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = NoDefaultCtorSource::class.java.name)
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { null }
        val error = result as TachiyomiLoadResult.Error
        assertEquals(TachiyomiLoadErrorPhase.INSTANTIATION, error.phase)
    }

    @Test
    fun `an instance that is not a Source is rejected`() {
        val pkg = pkgInfo(versionName = "1.4.1", sourceClass = NotASourceInstance::class.java.name)
        val result = TachiyomiApkLoaderRuntime.loadFromClass(info(pkg), testClassLoader) { null }
        val success = result as TachiyomiLoadResult.Success
        assertEquals(0, success.sources.size)
        assertEquals(1, success.rejections.size)
        assertTrue(success.rejections.single().reason.contains("not a Source"))
    }

    // ---- loadExtension (full orchestration + lib version gate) ----

    @Test
    fun `loadExtension rejects unsupported lib version for tsundoku`() {
        val pkg = pkgInfo(versionName = "1.5.2", sourceClass = FakeNovelSourceA::class.java.name)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val result = TachiyomiApkLoaderRuntime.loadExtension(
            spec = TachiyomiApkEcosystemSpecs.TSUNDOKU,
            pkgInfo = pkg,
            packageManager = packageManager,
            parentClassLoader = testClassLoader,
            prepareApkPath = { pkgName, sourcePath -> sourcePath },
            createClassLoader = { _, _, parent -> parent },
            validateSource = { null },
            getAppLabel = { "Label" },
        )
        val error = result as TachiyomiLoadResult.Error
        assertEquals(TachiyomiLoadErrorPhase.LIB_VERSION, error.phase)
        assertTrue(error.message.contains("Incompatible lib version: 1.5"))
    }

    @Test
    fun `loadExtension end-to-end loads a direct novel source`() {
        val pkg = pkgInfo(versionName = "1.6.1", sourceClass = FakeNovelSourceA::class.java.name)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val result = TachiyomiApkLoaderRuntime.loadExtension(
            spec = TachiyomiApkEcosystemSpecs.TSUNDOKU,
            pkgInfo = pkg,
            packageManager = packageManager,
            parentClassLoader = testClassLoader,
            prepareApkPath = { _, sourcePath -> sourcePath },
            createClassLoader = { _, _, parent -> parent },
            validateSource = { source -> if (source.isNovelSource()) null else "not novel" },
            getAppLabel = { "Label" },
        )
        val success = result as TachiyomiLoadResult.Success
        assertEquals("1.6", success.libVersion)
        assertEquals(1, success.sources.size)
        assertEquals("en", success.info.lang)
    }

    @Test
    fun `loadExtension end-to-end accepts tsundoku 1_4 and rejects a non-novel source`() {
        val pkg = pkgInfo(versionName = "1.4.2", sourceClass = FakeNovelFactory::class.java.name)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val result = TachiyomiApkLoaderRuntime.loadExtension(
            spec = TachiyomiApkEcosystemSpecs.TSUNDOKU,
            pkgInfo = pkg,
            packageManager = packageManager,
            parentClassLoader = testClassLoader,
            prepareApkPath = { _, sourcePath -> sourcePath },
            createClassLoader = { _, _, parent -> parent },
            validateSource = { source -> if (source.isNovelSource()) null else "Not a novel source (isNovelSource=false)" },
            getAppLabel = { "Label" },
        )
        val success = result as TachiyomiLoadResult.Success
        assertEquals(setOf(101L), success.sources.map { it.id }.toSet())
        assertEquals(1, success.rejections.size)
        assertNull(success.sources.firstOrNull { it.id == 102L })
    }
}
