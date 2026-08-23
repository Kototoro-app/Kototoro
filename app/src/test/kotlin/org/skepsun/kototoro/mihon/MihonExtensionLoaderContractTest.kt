package org.skepsun.kototoro.mihon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge
import org.skepsun.kototoro.mihon.model.MihonExtensionInfo
import org.skepsun.kototoro.mihon.model.MihonLoadResult

/**
 * Characterization tests for [MihonExtensionLoader]'s APK classification and structured
 * error reporting.
 *
 * These tests pin the CURRENT behavior of the loader so the Tsundoku extension integration
 * can refactor it without silent regressions. They only exercise deterministic branches:
 * classification (null vs non-null) and error paths. The success path requires real
 * extension class loading and is intentionally out of scope.
 *
 * Entry point: the public `loadExtension(context, packageName)` overload. The
 * `loadExtension(context, PackageInfo)` overload is private, so packages are fed in via a
 * mocked PackageManager.getPackageInfo, exactly as
 * `ExternalExtensionLoaderSupport.getPackageInfoOrNull` would surface them. Metadata bundles
 * are strict MockK mocks following the style of `ExternalExtensionMetadataSupportTest`: every
 * key the loader touches is stubbed explicitly (getString on a missing key is null;
 * getInt(key, default) must be stubbed with the default).
 */
class MihonExtensionLoaderContractTest {

	// Constructor dependencies of MihonExtensionLoader; all relaxed.
	private val applicationContext = mockk<Context>(relaxed = true)
	private val injektBridge = mockk<Lazy<KotoInjektBridge>>(relaxed = true)
	private val settings = mockk<AppSettings>(relaxed = true)
	private val packageManager = mockk<PackageManager>(relaxed = true)

	private fun newLoader(): MihonExtensionLoader {
		every { applicationContext.packageManager } returns packageManager
		// Relaxed-generic dagger.Lazy<T>.get() cannot be cast to KotoInjektBridge (type
		// erasure); stub it explicitly with a relaxed KotoInjektBridge mock.
		every { injektBridge.get() } returns mockk(relaxed = true)
		return MihonExtensionLoader(applicationContext, injektBridge, settings)
	}

	/**
	 * Feed [pkgInfo] through the public `loadExtension(context, packageName)` entry point,
	 * stubbing PackageManager to hand it back on lookup.
	 */
	private fun loadPackage(pkgInfo: PackageInfo): MihonLoadResult? {
		every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns pkgInfo
		val loader = newLoader()
		return runBlocking { loader.loadExtension(applicationContext, pkgInfo.packageName) }
	}

	private fun assertError(result: MihonLoadResult?, expectedMessagePart: String) {
		assertNotNull(result, "expected a load result, got null")
		assertTrue(
			result is MihonLoadResult.Error,
			"expected MihonLoadResult.Error, got $result",
		)
		val error = result as MihonLoadResult.Error
		assertTrue(
			error.message.contains(expectedMessagePart),
			"expected error message to contain \"$expectedMessagePart\", got \"${error.message}\"",
		)
	}

	private fun extensionFeature(): FeatureInfo = FeatureInfo().apply {
		name = EXTENSION_FEATURE
	}

	/**
	 * Strict metadata mock in the style of ExternalExtensionMetadataSupportTest: every Bundle
	 * method the loader may call on a manifest must be stubbed, otherwise the strict mock
	 * throws. Mirrors the production constant keys from MihonExtensionLoader.
	 */
	private fun mockExtensionMetadata(
		sourceClass: String? = null,
		sourceFactory: String? = null,
		nsfw: Int = 0,
	): Bundle {
		val metaData = mockk<Bundle>()
		every { metaData.containsKey(METADATA_SOURCE_CLASS) } returns (sourceClass != null)
		every { metaData.containsKey(METADATA_SOURCE_FACTORY) } returns (sourceFactory != null)
		every { metaData.getString(METADATA_SOURCE_CLASS) } returns sourceClass
		every { metaData.getString(METADATA_SOURCE_FACTORY) } returns sourceFactory
		every { metaData.containsKey(METADATA_CONTENT_WARNING) } returns false
		every { metaData.containsKey(METADATA_LIB_VERSION) } returns false
		every { metaData.getInt(METADATA_NSFW, 0) } returns nsfw
		// extractExtensionInfo logs metaData.keySet() on the no-declared-source error path.
		every { metaData.keySet() } returns emptySet()
		return metaData
	}

	// --- Classification: isPackageAnExtension -----------------------------------------

	@Test
	fun `package declaring the tachiyomi extension feature is classified as an extension`() {
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.modext"
			versionName = "1.5.0"
			applicationInfo = null
			reqFeatures = arrayOf(extensionFeature())
		}

		val result = loadPackage(pkgInfo)

		assertNotNull(result)
	}

	@Test
	fun `mihon named package with source class metadata is classified as an extension`() {
		val metaData = mockExtensionMetadata(
			sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source",
		)
		val appInfo = ApplicationInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			this.metaData = metaData
		}
		// versionName is incompatible on purpose: classification is what we assert here,
		// and an early error keeps the flow away from real class loading.
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionName = "1.1.0"
			applicationInfo = appInfo
			reqFeatures = emptyArray()
		}

		val result = loadPackage(pkgInfo)

		assertNotNull(result)
	}

	@Test
	fun `ordinary package with no mihon markers is rejected with null`() {
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.normal"
			metaData = null
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.normal"
			versionName = "1.5.0"
			applicationInfo = appInfo
			reqFeatures = emptyArray()
		}

		assertNull(loadPackage(pkgInfo))
	}

	@Test
	fun `mihon named package without source metadata is rejected with null`() {
		val appInfo = ApplicationInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			metaData = null
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionName = "1.5.0"
			applicationInfo = appInfo
			reqFeatures = emptyArray()
		}

		assertNull(loadPackage(pkgInfo))
	}

	@Test
	fun `source metadata without mihon name or feature is rejected with null`() {
		val metaData = mockExtensionMetadata(sourceClass = "org.example.Source")
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.reader"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.reader"
			versionName = "1.5.0"
			applicationInfo = appInfo
			reqFeatures = emptyArray()
		}

		assertNull(loadPackage(pkgInfo))
	}

	// --- Structured error paths: MihonLoadResult.Error ---------------------------------

	@Test
	fun `package without ApplicationInfo returns No ApplicationInfo error`() {
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionName = "1.5.0"
			applicationInfo = null
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "No ApplicationInfo")
	}

	@Test
	fun `package without version name returns No version name error`() {
		val metaData = mockExtensionMetadata(sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source")
		val appInfo = ApplicationInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionName = null
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "No version name")
	}

	@Test
	fun `package without manifest meta-data returns No meta-data in manifest error`() {
		val appInfo = ApplicationInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			metaData = null
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionName = "1.5.0"
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "No meta-data in manifest")
	}

	@Test
	fun `package without source class metadata returns No source class specified in manifest error`() {
		val metaData = mockExtensionMetadata() // neither class nor factory declared
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.modext"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.modext"
			versionName = "1.5.0"
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "No source class specified in manifest")
	}

	@Test
	fun `lib version 1_1_0 below minimum returns Incompatible lib version error`() {
		val metaData = mockExtensionMetadata(sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source")
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.modext"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.modext"
			versionName = "1.1.0"
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "Incompatible lib version")
	}

	@Test
	fun `lib version 2_0_0 above maximum returns Incompatible lib version error`() {
		val metaData = mockExtensionMetadata(sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source")
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.modext"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.modext"
			versionName = "2.0.0"
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "Incompatible lib version")
	}

	@Test
	fun `unparseable version name returns Invalid lib version format error`() {
		val metaData = mockExtensionMetadata(sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source")
		val appInfo = ApplicationInfo().apply {
			packageName = "com.example.modext"
			this.metaData = metaData
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "com.example.modext"
			versionName = "abc"
			applicationInfo = appInfo
			reqFeatures = arrayOf(extensionFeature())
		}

		assertError(loadPackage(pkgInfo), "Invalid lib version format")
	}

	// --- Metadata extraction contract (private API via reflection) ---------------------

	@Test
	fun `extractExtensionInfo maps manifest metadata into MihonExtensionInfo via reflection`() {
		val metaData = mockExtensionMetadata(
			sourceClass = "eu.kanade.tachiyomi.extension.en.sample.Source",
			nsfw = 1,
		)
		every { metaData.getString(METADATA_NEW_NAME) } returns "Sample Extension"
		val appInfo = ApplicationInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			this.metaData = metaData
			sourceDir = "/data/app/sample/base.apk"
		}
		val pkgInfo = PackageInfo().apply {
			packageName = "eu.kanade.tachiyomi.extension.en.sample"
			versionCode = 42
			versionName = "1.5.3"
			applicationInfo = appInfo
			reqFeatures = emptyArray()
		}

		val info = extractExtensionInfoReflectively(newLoader(), pkgInfo)

		assertNotNull(info)
		assertEquals("eu.kanade.tachiyomi.extension.en.sample", info?.pkgName)
		assertEquals("Sample Extension", info?.appName)
		assertEquals(42L, info?.versionCode)
		assertEquals("1.5.3", info?.versionName)
		assertEquals(1.5, info?.libVersion)
		assertEquals("en", info?.lang)
		assertEquals(true, info?.isNsfw)
		assertEquals("eu.kanade.tachiyomi.extension.en.sample.Source", info?.sourceClassName)
		assertEquals("/data/app/sample/base.apk", info?.apkPath)
	}

	private fun extractExtensionInfoReflectively(
		loader: MihonExtensionLoader,
		pkgInfo: PackageInfo,
	): MihonExtensionInfo? {
		val method = MihonExtensionLoader::class.java.getDeclaredMethod(
			"extractExtensionInfo",
			PackageInfo::class.java,
		)
		method.isAccessible = true
		return method.invoke(loader, pkgInfo) as? MihonExtensionInfo
	}

	private companion object {
		// Mirrors the private constants in MihonExtensionLoader; the strings themselves
		// are the external manifest contract this suite pins down.
		const val EXTENSION_FEATURE = "tachiyomi.extension"
		const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		const val METADATA_NEW_NAME = "tachiyomix.name"
		const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
		const val METADATA_LIB_VERSION = "tachiyomix.extensionLib"
	}
}
