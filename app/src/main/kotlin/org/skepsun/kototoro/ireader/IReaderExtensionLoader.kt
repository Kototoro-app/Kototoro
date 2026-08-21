package org.skepsun.kototoro.ireader

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ireader.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionMetadataSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.ireader.model.IReaderExtensionInfo
import org.skepsun.kototoro.ireader.model.IReaderLoadResult
import org.skepsun.kototoro.ireader.model.IReaderMangaSource
import org.skepsun.kototoro.mihon.util.ChildFirstPathClassLoader
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.skepsun.kototoro.core.prefs.AppSettings
import okhttp3.OkHttpClient

@Singleton
class IReaderExtensionLoader @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
    private val captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator,
) {
    companion object {
        private const val TAG = "IReaderExtensionLoader"
        private const val ECOSYSTEM_DIR = "ireader"

        private const val EXTENSION_FEATURE = "ireader"
        private const val METADATA_SOURCE_CLASS = "source.class"
        private const val METADATA_DESCRIPTION = "source.description"
        private const val METADATA_NSFW = "source.nsfw"
        private const val METADATA_ICON = "source.icon"

        const val LIB_VERSION_MIN = 2
        const val LIB_VERSION_MAX = 2
    }

    suspend fun loadExtensions(context: Context): List<IReaderLoadResult> = withContext(Dispatchers.IO) {
        try {
            val pkgManager = context.packageManager
            val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
            val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, ECOSYSTEM_DIR)

            val extPkgs = (installedPkgs + localPkgs)
                .filter { isPackageAnExtension(it) }
                .distinctBy { it.packageName }

            if (extPkgs.isEmpty()) {
                return@withContext emptyList()
            }

            extPkgs.map { pkgInfo ->
                async { loadExtension(context, pkgInfo) }
            }.awaitAll()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to load IReader extensions", e)
            emptyList()
        }
    }

    suspend fun loadExtension(context: Context, packageName: String): IReaderLoadResult? = withContext(Dispatchers.IO) {
        val pkgManager = context.packageManager
        val pkgInfo = ExternalExtensionLoaderSupport.getPackageInfoOrNull(pkgManager, packageName)
            ?: LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull(context, pkgManager, ECOSYSTEM_DIR, packageName)
            ?: return@withContext null

        if (!isPackageAnExtension(pkgInfo)) {
            return@withContext null
        }

        loadExtension(context, pkgInfo)
    }

    fun getInstalledExtensions(context: Context): List<IReaderExtensionInfo> {
        val pkgManager = context.packageManager
        val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
        val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, ECOSYSTEM_DIR)

        return (installedPkgs + localPkgs)
            .filter { isPackageAnExtension(it) }
            .distinctBy { it.packageName }
            .mapNotNull { extractExtensionInfo(it) }
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        // IReader extensions MUST specify the reqFeature "ireader"
        return pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
    }

    private fun extractExtensionInfo(pkgInfo: PackageInfo): IReaderExtensionInfo? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val metaData = ExternalExtensionMetadataSupport.getMetaDataOrNull(appInfo) ?: return null
        val versionName = pkgInfo.versionName ?: return null

        val libVersion = versionName.substringBefore('.').toDoubleOrNull() ?: 2.0

        val sourceClassName = metaData.getString(METADATA_SOURCE_CLASS)?.trim() ?: return null
        val isNsfw = metaData.getInt(METADATA_NSFW, 0) == 1

        val appName = try {
            ExternalExtensionLoaderSupport.getAppLabel(applicationContext, appInfo).removePrefix("IReader: ").trim()
        } catch (e: Exception) {
            pkgInfo.packageName.substringAfterLast('.')
        }

        return IReaderExtensionInfo(
            pkgName = pkgInfo.packageName,
            appName = appName,
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            versionName = versionName,
            libVersion = libVersion,
            isNsfw = isNsfw,
            sourceClassName = sourceClassName,
            apkPath = appInfo.sourceDir ?: return null,
        )
    }

    private fun loadExtension(context: Context, pkgInfo: PackageInfo): IReaderLoadResult {
        val pkgName = pkgInfo.packageName
        val appInfo = pkgInfo.applicationInfo ?: return IReaderLoadResult.Error(pkgName, "No ApplicationInfo")
        val versionName = pkgInfo.versionName ?: return IReaderLoadResult.Error(pkgName, "No version name")
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val metaData = ExternalExtensionMetadataSupport.getMetaDataOrNull(appInfo)
            ?: return IReaderLoadResult.Error(pkgName, "No meta-data in manifest")

        val sourceClassName = metaData.getString(METADATA_SOURCE_CLASS)?.trim()
            ?: return IReaderLoadResult.Error(pkgName, "No source.class specified in manifest")

        val classToLoad = if (sourceClassName.startsWith(".")) {
            pkgInfo.packageName + sourceClassName
        } else {
            sourceClassName
        }

        val isNsfw = metaData.getInt(METADATA_NSFW, 0) == 1
        val appName = ExternalExtensionLoaderSupport.getAppLabel(context, appInfo).removePrefix("IReader: ").trim()

        val classLoader = try {
            val dexPath = LocalApkExtensionSupport.prepareLoadableApkPath(
                context = context,
                ecosystem = ECOSYSTEM_DIR,
                pkgName = pkgName,
                sourcePath = appInfo.sourceDir,
            )
            ChildFirstPathClassLoader(dexPath, appInfo.nativeLibraryDir, context.classLoader)
        } catch (e: Throwable) {
            return IReaderLoadResult.Error(pkgName, "Failed to create ClassLoader", e)
        }

        val source = try {
            android.util.Log.d(TAG, "Loading extension: pkg=$pkgName classToLoad=$classToLoad sourceDir=${appInfo.sourceDir} nativeLibDir=${appInfo.nativeLibraryDir}")
            val clazz = Class.forName(classToLoad, false, classLoader)
            val dependenciesConstructor = clazz.constructors.firstOrNull { constructor ->
                constructor.parameterTypes.size == 1 &&
                    constructor.parameterTypes[0].isAssignableFrom(ireader.core.source.Dependencies::class.java)
            }

            if (dependenciesConstructor != null) {
                val session = IReaderDependenciesProvider.createSession(
                    context = context,
                    kototoroClient = httpClient,
                    captchaCoordinator = captchaAutoResolveCoordinator,
                )
                (dependenciesConstructor.newInstance(session.dependencies) as Source).also { loadedSource ->
                    session.bindSource(
                        IReaderMangaSource(
                            pkgName = pkgName,
                            catalogueSource = loadedSource,
                            isNsfw = isNsfw,
                            language = loadedSource.lang,
                            displayName = loadedSource.name,
                        ),
                    )
                }
            } else {
                // Try parameterless constructor
                clazz.getDeclaredConstructor().newInstance() as Source
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to load IReader source from $pkgName", e)
            return IReaderLoadResult.Error(pkgName, "Failed to load source: ${e.message}", e)
        }

        val libVersion = versionName.substringBefore('.').toDoubleOrNull() ?: 2.0

        return IReaderLoadResult.Success(
            pkgName = pkgName,
            appName = appName,
            versionCode = versionCode,
            versionName = versionName,
            libVersion = libVersion,
            isNsfw = isNsfw,
            sources = listOf(source),
            isManagedLocal = LocalApkExtensionSupport.isManagedLocalPackage(context, ECOSYSTEM_DIR, appInfo.sourceDir),
        )
    }
}
