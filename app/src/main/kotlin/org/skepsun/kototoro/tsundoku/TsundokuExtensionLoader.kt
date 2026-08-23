package org.skepsun.kototoro.tsundoku

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.NovelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.extensions.runtime.tachiyomi.ExternalApkCandidateResolver
import org.skepsun.kototoro.extensions.runtime.tachiyomi.ExternalApkCandidateSelection
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkClassification
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkClassifier
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkEcosystemSpecs
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkLoaderRuntime
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiLoadResult
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loader for Tsundoku novel extension APKs (plan T2A.2-T2A.5).
 *
 * Uses the shared strict classifier + version-first candidate resolver + [TachiyomiApkLoaderRuntime]
 * so the Tsundoku semantics (feature-only identification, novel/src validation, structured
 * errors, ambiguous rejection) live in exactly one place.
 */
@Singleton
class TsundokuExtensionLoader @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val injektBridge: dagger.Lazy<KotoInjektBridge>,
    private val accessors: TsundokuApkAccessors,
) {
    companion object {
        private const val TAG = "TsundokuExtensionLoader"
        val SPEC = TachiyomiApkEcosystemSpecs.TSUNDOKU

        private fun ambiguousMessage(pkgName: String): String =
            "$pkgName declares both 'tachiyomi.novelextension' and 'tachiyomi.extension'; " +
            "ambiguous extension refused (plan §7.2)"
    }

    /**
     * Scan + load every Tsundoku extension (installed + app-private local), returning one
     * structured result per candidate package. Ambiguous packages produce a structured error.
     */
    suspend fun loadExtensions(context: Context): List<TsundokuLoadResult> = withContext(Dispatchers.IO) {
        try {
            injektBridge.get().initialize()

            val pkgManager = context.packageManager
            val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
            val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, SPEC.ecosystemDir)
            val candidates = ExternalApkCandidateResolver.resolve(
                installed = installedPkgs,
                local = localPkgs,
                mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
            )

            val results = mutableListOf<TsundokuLoadResult>()
            for (pkg in candidates) {
                when (TachiyomiApkClassifier.classify(pkg, SPEC)) {
                    TachiyomiApkClassification.Extension -> results += loadExtension(context, pkg)
                    TachiyomiApkClassification.Ambiguous -> results += TsundokuLoadResult.Error(
                        pkgName = pkg.packageName,
                        phase = "AMBIGUOUS",
                        message = ambiguousMessage(pkg.packageName),
                    )
                    TachiyomiApkClassification.NotAnExtension -> Unit
                }
            }
            results
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Tsundoku extensions", e)
            emptyList()
        }
    }

    /** Load a single Tsundoku extension by package name (or null when it is not a Tsundoku ext). */
    suspend fun loadExtension(context: Context, packageName: String): TsundokuLoadResult? = withContext(Dispatchers.IO) {
        injektBridge.get().initialize()

        val pkgManager = context.packageManager
        val pkgInfo = ExternalExtensionLoaderSupport.getPackageInfoOrNull(pkgManager, packageName)
            ?: LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull(context, pkgManager, SPEC.ecosystemDir, packageName)
            ?: return@withContext null

        when (TachiyomiApkClassifier.classify(pkgInfo, SPEC)) {
            TachiyomiApkClassification.Extension -> loadExtension(context, pkgInfo)
            TachiyomiApkClassification.Ambiguous -> TsundokuLoadResult.Error(
                pkgName = packageName,
                phase = "AMBIGUOUS",
                message = ambiguousMessage(packageName),
            )
            TachiyomiApkClassification.NotAnExtension -> null
        }
    }

    /** Installed/local Tsundoku extension metadata without class loading. */
    fun getInstalledExtensions(context: Context): List<TsundokuExtensionInfo> {
        val pkgManager = context.packageManager
        val installedPkgs = ExternalExtensionLoaderSupport.getInstalledPackages(pkgManager)
        val localPkgs = LocalApkExtensionSupport.getLocalArchivePackages(context, pkgManager, SPEC.ecosystemDir)

        return ExternalApkCandidateResolver.resolve(
            installed = installedPkgs,
            local = localPkgs,
            mode = ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM,
        )
            .filter { TachiyomiApkClassifier.classify(it, SPEC) == TachiyomiApkClassification.Extension }
            .mapNotNull { extractExtensionInfo(it) }
    }

    private fun extractExtensionInfo(pkgInfo: PackageInfo): TsundokuExtensionInfo? {
        val infoResult = TachiyomiApkLoaderRuntime.extractExtensionInfo(
            spec = SPEC,
            pkgInfo = pkgInfo,
            getAppLabel = { appInfo -> ExternalExtensionLoaderSupport.getAppLabel(applicationContext, appInfo) },
        )
        val info = (infoResult as? TachiyomiApkLoaderRuntime.InfoResult.Ok)?.info ?: return null
        return TsundokuExtensionInfo(
            pkgName = info.packageName,
            appName = info.appName,
            versionCode = info.versionCode,
            versionName = info.versionName,
            libVersion = info.libVersion.toDoubleOrNull() ?: 0.0,
            lang = info.lang,
            isNsfw = info.isNsfw,
            sourceClassName = info.sourceClassName,
            apkPath = info.apkPath,
        )
    }

    private fun loadExtension(context: Context, pkgInfo: PackageInfo): TsundokuLoadResult {
        val result = TachiyomiApkLoaderRuntime.loadExtension(
            spec = SPEC,
            pkgInfo = pkgInfo,
            packageManager = context.packageManager,
            parentClassLoader = context.classLoader,
            prepareApkPath = { pkgName, sourcePath ->
                accessors.prepareApkPath(context, SPEC.ecosystemDir, pkgName, sourcePath)
            },
            createClassLoader = { dexPath, nativeLibDir, parent ->
                accessors.createClassLoader(dexPath, nativeLibDir, parent)
            },
            validateSource = { source ->
                if (source.isNovelSource() || source is NovelSource) {
                    null
                } else {
                    "Not a novel source (isNovelSource=false)"
                }
            },
            getAppLabel = { appInfo -> ExternalExtensionLoaderSupport.getAppLabel(context, appInfo) },
        )

        return when (result) {
            is TachiyomiLoadResult.Success -> TsundokuLoadResult.Success(
                pkgName = result.info.packageName,
                appName = result.info.appName,
                versionCode = result.info.versionCode,
                versionName = result.info.versionName,
                libVersion = result.info.libVersion.toDoubleOrNull() ?: 0.0,
                lang = result.info.lang,
                isNsfw = result.info.isNsfw,
                sources = result.sources,
                rejections = result.rejections,
                isManagedLocal = runCatching {
                    LocalApkExtensionSupport.isManagedLocalPackage(
                        context,
                        SPEC.ecosystemDir,
                        pkgInfo.applicationInfo?.sourceDir.orEmpty(),
                    )
                }.getOrDefault(false),
            )

            is TachiyomiLoadResult.Error -> TsundokuLoadResult.Error(
                pkgName = result.packageName,
                phase = result.phase.name,
                message = result.message,
                exception = result.cause,
            )
        }
    }
}
