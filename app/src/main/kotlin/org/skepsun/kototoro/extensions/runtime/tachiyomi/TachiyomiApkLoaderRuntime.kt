package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionMetadataSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionSourceLoaderSupport

/**
 * Ecosystem-driven APK metadata / ABI / load-path / structured-error runtime shared by every
 * Tachiyomi-ABI ecosystem (plan §6.1 "TachiyomiApkLoaderRuntime", T1.2 + T2A.2/T2A.3).
 *
 * The runtime is spec-driven ([TachiyomiApkEcosystemSpec]): Mihon keeps its loose rules and
 * 1.2..1.9 range, Tsundoku only accepts the strict `tachiyomi.novelextension` feature and
 * lib 1.4/1.6. Everything Android/os-specific (scanning, ClassLoader construction, local APK
 * staging) is injected so the decisions are unit-testable without a device.
 */
object TachiyomiApkLoaderRuntime {

    private const val TAG = "TachiyomiApkLoaderRuntime"

    /** Metadata extracted from one extension APK before class loading. */
    data class TachiyomiApkInfo(
        val ecosystem: ExternalExtensionType,
        val packageName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
        val libVersion: String,
        val lang: String,
        val isNsfw: Boolean,
        val sourceClassName: String,
        val apkPath: String?,
    )

    sealed interface InfoResult {
        data class Ok(val info: TachiyomiApkInfo) : InfoResult
        data class Err(val error: TachiyomiLoadResult.Error) : InfoResult
    }

    /**
     * Structured metadata extraction (mirrors the Mihon error taxonomy: "No ApplicationInfo",
     * "No version name", "No meta-data in manifest", "No source class specified in manifest").
     */
    fun extractExtensionInfo(
        spec: TachiyomiApkEcosystemSpec,
        pkgInfo: PackageInfo,
        getAppLabel: (ApplicationInfo) -> String,
    ): InfoResult {
        val pkgName = pkgInfo.packageName
        val appInfo = pkgInfo.applicationInfo ?: return infoError(TachiyomiLoadErrorPhase.METADATA, pkgName, "No ApplicationInfo")
        val versionName = pkgInfo.versionName ?: return infoError(TachiyomiLoadErrorPhase.METADATA, pkgName, "No version name")
        val versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo)
        val metaData = appInfo.metaData ?: return infoError(TachiyomiLoadErrorPhase.METADATA, pkgName, "No meta-data in manifest")

        val declaredSource = ExternalExtensionMetadataSupport.getDeclaredSourceMetadataOrNull(
            metaData = metaData,
            sourceClassKey = spec.sourceMetadataKey,
            sourceFactoryKey = spec.factoryMetadataKey.orEmpty(),
            nsfwKey = spec.nsfwMetadataKey,
        ) ?: return infoError(TachiyomiLoadErrorPhase.METADATA, pkgName, "No source class specified in manifest")

        val libVersion = declaredSource.libVersionOverride?.let(::formatLibVersion)
            ?: run {
                val parsed = versionName.substringBeforeLast('.').toDoubleOrNull()
                parsed?.let(::formatLibVersion)
            }
            ?: return infoError(TachiyomiLoadErrorPhase.METADATA, pkgName, "Invalid lib version format: $versionName")

        val appName = metaData.getString("tachiyomix.name") ?: run {
            try {
                getAppLabel(appInfo)
            } catch (_: Exception) {
                pkgName.substringAfterLast('.')
            }
        }

        return InfoResult.Ok(
            TachiyomiApkInfo(
                ecosystem = spec.extensionType,
                packageName = pkgName,
                appName = appName,
                versionCode = versionCode,
                versionName = versionName,
                libVersion = libVersion,
                lang = ExternalExtensionLoaderSupport.extractLanguage(pkgName, spec.languageMarker),
                isNsfw = declaredSource.isNsfw,
                sourceClassName = declaredSource.sourceClassName,
                apkPath = appInfo.sourceDir,
            ),
        )
    }

    /**
     * Full load of one package: metadata (via [extractExtensionInfo]) → lib-version gate →
     * ClassLoader → direct/factory instantiation → per-source validation (novel contract +
     * duplicate-id) and per-source error isolation (plan §6.3).
     *
     * @param validateSource returns the rejection reason, or null when the source is acceptable
     *   (e.g. Tsundoku passes `{ it.isNovelSource || it is NovelSource }`).
     */
    fun loadExtension(
        spec: TachiyomiApkEcosystemSpec,
        pkgInfo: PackageInfo,
        packageManager: PackageManager,
        parentClassLoader: ClassLoader,
        prepareApkPath: (pkgName: String, sourcePath: String) -> String,
        createClassLoader: (dexPath: String, nativeLibDir: String?, parent: ClassLoader) -> ClassLoader,
        validateSource: (source: Source) -> String?,
        getAppLabel: (ApplicationInfo) -> String,
    ): TachiyomiLoadResult {
        val completePkgInfo = ExternalExtensionLoaderSupport.refreshPackageInfoIfNeeded(packageManager, pkgInfo)
        val infoResult = extractExtensionInfo(spec, completePkgInfo, getAppLabel)
        if (infoResult is InfoResult.Err) return infoResult.error
        val info = (infoResult as InfoResult.Ok).info

        if (!spec.acceptedLibVersions.contains(info.libVersion)) {
            return TachiyomiLoadResult.Error(
                info.packageName,
                TachiyomiLoadErrorPhase.LIB_VERSION,
                "Incompatible lib version: ${info.libVersion} (supported: ${spec.acceptedLibVersions.sorted().joinToString("-")})",
            )
        }

        val apkPath = info.apkPath ?: return TachiyomiLoadResult.Error(
            info.packageName,
            TachiyomiLoadErrorPhase.METADATA,
            "No APK path",
        )

        val classLoader = try {
            val dexPath = prepareApkPath(info.packageName, apkPath)
            createClassLoader(dexPath, completePkgInfo.applicationInfo?.nativeLibraryDir, parentClassLoader)
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtension(${info.packageName}) FAILED: classloader", e)
            return TachiyomiLoadResult.Error(
                info.packageName,
                TachiyomiLoadErrorPhase.CLASSLOADER,
                "Failed to create ClassLoader",
                e,
            )
        }

        return loadFromClass(info, classLoader, validateSource)
    }

    /**
     * Instantiate sources from [info.sourceClassName] through [classLoader] and apply
     * [validateSource] with per-source error isolation.
     */
    fun loadFromClass(
        info: TachiyomiApkInfo,
        classLoader: ClassLoader,
        validateSource: (Source) -> String?,
    ): TachiyomiLoadResult {
        val loaded: MutableList<Source> = mutableListOf()
        val rejections: MutableList<TachiyomiSourceRejection> = mutableListOf()
        val seenIds = HashSet<Long>()

        for (fullClassName in ExternalExtensionSourceLoaderSupport.resolveSourceClassNames(info.packageName, info.sourceClassName)) {
            val instance = try {
                val clazz = Class.forName(fullClassName, false, classLoader)
                clazz.getDeclaredConstructor().newInstance()
            } catch (e: Throwable) {
                Log.e(TAG, "loadFromClass(${info.packageName}) FAILED: instantiate $fullClassName", e)
                return TachiyomiLoadResult.Error(
                    info.packageName,
                    TachiyomiLoadErrorPhase.INSTANTIATION,
                    "Failed to load source class $fullClassName: ${e.message}",
                    e,
                )
            }

            when (instance) {
                is SourceFactory -> {
                    val factorySources = try {
                        instance.createSources()
                    } catch (e: Throwable) {
                        // Plan §6.3: a throwing factory can only fail the whole package.
                        Log.e(TAG, "loadFromClass(${info.packageName}) FAILED: factory $fullClassName", e)
                        return TachiyomiLoadResult.Error(
                            info.packageName,
                            TachiyomiLoadErrorPhase.INSTANTIATION,
                            "SourceFactory $fullClassName failed to create sources: ${e.message}",
                            e,
                        )
                    }
                    factorySources.forEach { source -> collectSource(info, source, fullClassName, loaded, rejections, seenIds, validateSource) }
                }

                is Source -> collectSource(info, instance, fullClassName, loaded, rejections, seenIds, validateSource)

                else -> rejections += TachiyomiSourceRejection(fullClassName, "Instance is not a Source (got ${instance.javaClass.name})")
            }
        }

        return TachiyomiLoadResult.Success(
            packageName = info.packageName,
            libVersion = info.libVersion,
            isNsfw = info.isNsfw,
            sources = loaded,
            rejections = rejections,
            info = info,
        )
    }

    private fun collectSource(
        info: TachiyomiApkInfo,
        source: Source,
        className: String,
        loaded: MutableList<Source>,
        rejections: MutableList<TachiyomiSourceRejection>,
        seenIds: HashSet<Long>,
        validateSource: (Source) -> String?,
    ) {
        if (!seenIds.add(source.id)) {
            rejections += TachiyomiSourceRejection(className, "Duplicate source ID: ${source.id}")
            return
        }
        val reason = validateSource(source)
        if (reason != null) {
            rejections += TachiyomiSourceRejection(className, reason)
            return
        }
        loaded += source
    }

    private fun formatLibVersion(version: Double): String = version.toString()

    private fun infoError(
        phase: TachiyomiLoadErrorPhase,
        pkgName: String,
        message: String,
        cause: Throwable? = null,
    ): InfoResult.Err = InfoResult.Err(TachiyomiLoadResult.Error(pkgName, phase, message, cause))
}
