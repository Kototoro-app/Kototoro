package org.skepsun.kototoro.aniyomi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionMetadataSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionSourceLoaderSupport
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.aniyomi.compat.KotoAniyomiInjektBridge
import org.skepsun.kototoro.aniyomi.model.AniyomiExtensionInfo
import org.skepsun.kototoro.aniyomi.model.AniyomiLoadResult
import org.skepsun.kototoro.aniyomi.util.ChildFirstPathClassLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loader for Aniyomi extension APKs.
 *
 * Scans for installed Aniyomi extensions and loads their AnimeSource implementations.
 */
@Singleton
class AniyomiExtensionLoader @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val injektBridge: dagger.Lazy<KotoAniyomiInjektBridge>,
    private val settings: AppSettings,
) {
    companion object {
        private const val TAG = "AniyomiExtensionLoader"
        private const val ECOSYSTEM_DIR = "aniyomi"

        // Feature that marks an APK as an Aniyomi anime extension
        private const val EXTENSION_FEATURE = "tachiyomi.animeextension"

        // Metadata keys in AndroidManifest.xml
        private const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
        private const val METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
        private const val METADATA_NSFW = "tachiyomi.animeextension.nsfw"

        // Supported library version range for Aniyomi
        const val LIB_VERSION_MIN = 12.0
        const val LIB_VERSION_MAX = 16.0

    }

    /**
     * Load all installed Aniyomi extensions.
     */
    suspend fun loadExtensions(context: Context): List<AniyomiLoadResult> = withContext(Dispatchers.IO) {
        try {
            // Ensure Injekt is initialized before loading any extensions
            injektBridge.get().initialize()

            val pkgManager = context.packageManager

            // Get all installed packages
            val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
            val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, ECOSYSTEM_DIR)
            android.util.Log.d(TAG, "Filtering ${installedPkgs.size} packages...")

            // Filter to only extension packages
            val extPkgs = (installedPkgs + localPkgs)
                .filter { pkgInfo: PackageInfo -> isPackageAnExtension(pkgInfo) }
                .distinctBy { it.packageName }

            if (extPkgs.isEmpty()) {
                android.util.Log.d(TAG, "No Aniyomi extensions found")
                return@withContext emptyList()
            }

            android.util.Log.d(TAG, "Found ${extPkgs.size} Aniyomi extension(s)")

            // Load extensions in parallel
            extPkgs.map { pkgInfo: PackageInfo ->
                async { loadExtension(context, pkgInfo) }
            }.awaitAll()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to load Aniyomi extensions", e)
            emptyList()
        }
    }

    /**
     * Load a single Aniyomi extension by package name.
     */
    suspend fun loadExtension(context: Context, packageName: String): AniyomiLoadResult? = withContext(Dispatchers.IO) {
        injektBridge.get().initialize()

        val pkgManager = context.packageManager
        val pkgInfo = ExternalExtensionLoaderSupport.getPackageInfoOrNull(pkgManager, packageName)
            ?: LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull(context, pkgManager, ECOSYSTEM_DIR, packageName)
            ?: return@withContext null

        if (!isPackageAnExtension(pkgInfo)) {
            return@withContext null
        }

        loadExtension(context, pkgInfo)
    }

    /**
     * Get list of installed Aniyomi extensions (metadata only, without loading).
     */
    fun getInstalledExtensions(context: Context): List<AniyomiExtensionInfo> {
        val pkgManager = context.packageManager
        val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
        val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, ECOSYSTEM_DIR)

        return (installedPkgs + localPkgs)
            .filter { isPackageAnExtension(it) }
            .distinctBy { it.packageName }
            .mapNotNull { extractExtensionInfo(it) }
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        val pkgName = pkgInfo.packageName

        // Method 1: Check for explicit feature declaration
        val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true

        // Method 2: Check for package naming convention
        val hasPackageName = ExternalExtensionLoaderSupport.looksLikeAniyomiPackage(pkgName)

        // Method 3: Check for metadata in application info
        val hasMetaData = ExternalExtensionMetadataSupport.hasDeclaredSource(
            metaData = pkgInfo.applicationInfo?.metaData,
            sourceClassKey = METADATA_SOURCE_CLASS,
            sourceFactoryKey = METADATA_SOURCE_FACTORY,
        )

        val isExtension = hasFeature || (hasPackageName && hasMetaData)

        if (BuildConfig.DEBUG && (hasPackageName || isExtension)) {
            android.util.Log.d(TAG, "isPackageAnExtension($pkgName): isExt=$isExtension (feature=$hasFeature, name=$hasPackageName, meta=$hasMetaData)")
            if (!hasFeature) {
                val features = pkgInfo.reqFeatures?.joinToString { it.name } ?: "none"
                android.util.Log.d(TAG, "$pkgName missing required feature $EXTENSION_FEATURE. Current features: $features")
            }
        }

        return isExtension
    }

    private fun extractExtensionInfo(pkgInfo: PackageInfo): AniyomiExtensionInfo? {
        val completePkgInfo = ExternalExtensionLoaderSupport.refreshPackageInfoIfNeeded(
            applicationContext.packageManager,
            pkgInfo,
        )
        val pkgName = completePkgInfo.packageName
        val appInfo = completePkgInfo.applicationInfo ?: run {
            android.util.Log.w(TAG, "extractExtensionInfo($pkgName): skipped because applicationInfo is null")
            return null
        }
        val metaData = ExternalExtensionMetadataSupport.getMetaDataOrNull(appInfo) ?: run {
            android.util.Log.w(TAG, "extractExtensionInfo($pkgName): skipped because metaData is null")
            return null
        }

        val versionName = completePkgInfo.versionName ?: run {
            android.util.Log.w(TAG, "extractExtensionInfo($pkgName): skipped because versionName is null")
            return null
        }

        // Aniyomi lib version is usually the first part of the version name
        val libVersion = try {
            versionName.substringBefore('.').toDoubleOrNull() ?: 12.0
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractExtensionInfo($pkgName): Failed to parse libVersion from $versionName, defaulting to 12.0")
            12.0
        }

        val declaredSource = ExternalExtensionMetadataSupport.getDeclaredSourceMetadataOrNull(
            metaData = metaData,
            sourceClassKey = METADATA_SOURCE_CLASS,
            sourceFactoryKey = METADATA_SOURCE_FACTORY,
            nsfwKey = METADATA_NSFW,
        ) ?: run {
            android.util.Log.w(TAG, "extractExtensionInfo($pkgName): skipped because no declaredSource could be parsed. Keys present in manifest: ${metaData.keySet()?.joinToString()}")
            return null
        }

        val appName = ExternalExtensionLoaderSupport.getAppLabel(applicationContext, appInfo)
        val lang = ExternalExtensionLoaderSupport.extractLanguage(completePkgInfo.packageName, "animeextension")

        return AniyomiExtensionInfo(
            pkgName = completePkgInfo.packageName,
            appName = appName,
            versionCode = PackageInfoCompat.getLongVersionCode(completePkgInfo),
            versionName = versionName,
            libVersion = libVersion,
            lang = lang,
            isNsfw = declaredSource.isNsfw,
            sourceClassName = declaredSource.sourceClassName,
            apkPath = appInfo.sourceDir ?: return null,
        )
    }

    private fun loadExtension(context: Context, pkgInfo: PackageInfo): AniyomiLoadResult {
        val completePkgInfo = ExternalExtensionLoaderSupport.refreshPackageInfoIfNeeded(
            context.packageManager,
            pkgInfo,
        )
        val pkgName = completePkgInfo.packageName
        val appInfo = completePkgInfo.applicationInfo
            ?: run {
                android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: No ApplicationInfo")
                return AniyomiLoadResult.Error(pkgName, "No ApplicationInfo")
            }

        val versionName = completePkgInfo.versionName
            ?: run {
                android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: No version name")
                return AniyomiLoadResult.Error(pkgName, "No version name")
            }
        val versionCode = PackageInfoCompat.getLongVersionCode(completePkgInfo)

        val libVersion = versionName.substringBefore('.').toDoubleOrNull()
            ?: run {
                android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: Invalid lib version format ($versionName)")
                return AniyomiLoadResult.Error(pkgName, "Invalid lib version format: $versionName")
            }

        if (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
            val err = "Incompatible lib version: $libVersion (supported: $LIB_VERSION_MIN-$LIB_VERSION_MAX) for versionName=$versionName"
            android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: $err")
            return AniyomiLoadResult.Error(pkgName, err)
        }

        val metaData = ExternalExtensionMetadataSupport.getMetaDataOrNull(appInfo)
            ?: run {
                android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: No meta-data in manifest")
                return AniyomiLoadResult.Error(pkgName, "No meta-data in manifest")
            }

        val declaredSource = ExternalExtensionMetadataSupport.getDeclaredSourceMetadataOrNull(
            metaData = metaData,
            sourceClassKey = METADATA_SOURCE_CLASS,
            sourceFactoryKey = METADATA_SOURCE_FACTORY,
            nsfwKey = METADATA_NSFW,
        )
            ?: run {
                android.util.Log.e(TAG, "loadExtension($pkgName) FAILED: No valid source class specified in manifest keys ${metaData.keySet()?.joinToString()}")
                return AniyomiLoadResult.Error(pkgName, "No source class specified in manifest")
            }
        val appName = ExternalExtensionLoaderSupport.getAppLabel(context, appInfo)
        val lang = ExternalExtensionLoaderSupport.extractLanguage(pkgName, "animeextension")

        val classLoader = try {
            val apkFile = java.io.File(appInfo.sourceDir)
            android.util.Log.i(TAG, "Loading extension $pkgName from ${appInfo.sourceDir} (exists=${apkFile.exists()}, readable=${apkFile.canRead()}, size=${apkFile.length()})")

            if (!apkFile.exists() || !apkFile.canRead()) {
                android.util.Log.e(TAG, "Extension APK file is missing or not readable!")
            }

            val dexPath = LocalApkExtensionSupport.prepareLoadableApkPath(
                context = context,
                ecosystem = ECOSYSTEM_DIR,
                pkgName = pkgName,
                sourcePath = appInfo.sourceDir,
            )

            ChildFirstPathClassLoader(
                dexPath,
                appInfo.nativeLibraryDir,
                context.classLoader
            )
        } catch (e: Throwable) {
            return AniyomiLoadResult.Error(pkgName, "Failed to create ClassLoader", e)
        }

        val sources = try {
            loadSources(pkgName, declaredSource.sourceClassName, classLoader)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to load sources from $pkgName", e)
            return AniyomiLoadResult.Error(pkgName, "Failed to load sources: ${e.message}", e)
        }

        return AniyomiLoadResult.Success(
            pkgName = pkgName,
            appName = appName,
            versionCode = versionCode,
            versionName = versionName,
            libVersion = libVersion,
            lang = lang,
            isNsfw = declaredSource.isNsfw,
            sources = sources,
            isManagedLocal = LocalApkExtensionSupport.isManagedLocalPackage(context, ECOSYSTEM_DIR, appInfo.sourceDir),
        )
    }

    private fun loadSources(
        pkgName: String,
        sourceClassNames: String,
        classLoader: ClassLoader,
    ): List<AnimeSource> {
        return ExternalExtensionSourceLoaderSupport.loadSources(
            pkgName = pkgName,
            sourceClassNames = sourceClassNames,
            classLoader = classLoader,
            asSource = { it as? AnimeSource },
            createSourcesFromFactory = { (it as? AnimeSourceFactory)?.createSources() },
            onUnknownInstance = { className ->
                android.util.Log.w(TAG, "Unknown instance type: $className")
            },
        )
    }

}
