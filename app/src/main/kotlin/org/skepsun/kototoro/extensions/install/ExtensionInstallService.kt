package org.skepsun.kototoro.extensions.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPluginCompatibility
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPluginCompatibilityChecker
import org.skepsun.kototoro.core.exceptions.IncompatiblePluginException
import org.skepsun.kototoro.core.exceptions.MissingPluginHostClassesException
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.repo.gitHubArchiveCandidates
import org.skepsun.kototoro.extensions.repo.toInstalledPackageName
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionInstallService @Inject constructor(
    @ApplicationContext private val context: Context,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
    private val mirrorRepository: GitHubMirrorCatalogRepository,
    private val cloudstreamRuntimeManager: org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager,
    private val systemPackageInstaller: SystemPackageInstaller,
    private val sourceOriginsDao: org.skepsun.kototoro.core.db.dao.SourceOriginsDao,
) {
    private val githubHttpClient by lazy {
        httpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private fun downloadClient(url: String): OkHttpClient {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        return if (host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")) {
            githubHttpClient
        } else {
            httpClient
        }
    }

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val _downloadStates = MutableStateFlow<Map<String, ExtensionInstallDownloadState>>(emptyMap())

    val downloadStates: StateFlow<Map<String, ExtensionInstallDownloadState>> = _downloadStates.asStateFlow()

    private val _installing = MutableStateFlow<Set<String>>(emptySet())

    /** Packages with an [install] call in flight (covers the post-download phase, unlike [downloadStates]). */
    val installing: StateFlow<Set<String>> = _installing.asStateFlow()

    suspend fun install(
        extension: RepoAvailableExtension,
        mode: ExtensionInstallMode = ExtensionInstallMode.LOCAL_APK,
    ): ExtensionInstallResult {
        val pkg = extension.pkgName
        var added = false
        _installing.update { current ->
            added = pkg !in current
            if (added) current + pkg else current
        }
        if (!added) {
            // Another install for this package is already running (e.g. double tap): ignore this request.
            return ExtensionInstallResult.Completed
        }
        try {
            return performInstall(extension, mode)
        } finally {
            _installing.update { it - pkg }
        }
    }

    private suspend fun performInstall(
        extension: RepoAvailableExtension,
        mode: ExtensionInstallMode,
    ): ExtensionInstallResult {
        val rawUrl = extension.archiveUrl ?: when (extension.type) {
            ExternalExtensionType.CLOUDSTREAM -> "${extension.repoUrl}/${extension.archiveName}"
            else -> "${extension.repoUrl}/apk/${extension.archiveName}"
        }
        val mirrorEntry = mirrorRepository.entry(settings.gitHubMirrorId) ?: GitHubMirrorCatalog.NATIVE
        val candidates = gitHubArchiveCandidates(rawUrl, mirrorEntry, mirrorRepository.entries.value)
        val outputDir = File(context.cacheDir, "extension-installs").apply { mkdirs() }
        val archiveExtension = extension.archiveName.substringAfterLast('.', missingDelimiterValue = "apk")
        val outputFile = File(outputDir, "${extension.pkgName}-${extension.versionCode}.$archiveExtension")
        var lastError: Exception? = null
        var downloaded = false
        for (archiveUrl in candidates) {
            val call = downloadClient(archiveUrl)
                .newCachelessCallWithProgress(GET(archiveUrl), ExtensionInstallProgressListener(extension.pkgName))
            if (activeCalls.putIfAbsent(extension.pkgName, call) != null) {
                // A download for this package is already running (e.g. double tap): ignore this request.
                return ExtensionInstallResult.Completed
            }
            // Initial state: published without arming the throttle so the first real tick is not swallowed.
            _downloadStates.update { it + (extension.pkgName to ExtensionInstallDownloadState(extension.pkgName, 0L, -1L)) }
            try {
                call.awaitSuccess().use { response ->
                    val body = requireNotNull(response.body) { "Missing APK response body" }
                    outputFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                downloaded = true
                break
            } catch (e: IOException) {
                if (call.isCanceled()) {
                    throw CancellationException("Extension install download cancelled for ${extension.pkgName}", e)
                }
                // Mirror miss (blocked host / 404 through a proxy): fall through to the next candidate.
                android.util.Log.w("KototoroInit", "install download via $archiveUrl failed: ${e.message}")
                lastError = e
            } catch (e: HttpException) {
                // awaitSuccess() reports non-2xx responses as HttpException: treat them like a mirror miss too.
                android.util.Log.w("KototoroInit", "install download via $archiveUrl failed: HTTP ${e.code}")
                lastError = e
            } finally {
                activeCalls.remove(extension.pkgName)
                lastProgressEmitNanos.remove(extension.pkgName)
                _downloadStates.update { it - extension.pkgName }
            }
        }
        if (!downloaded) {
            throw lastError ?: IOException("No download candidate succeeded for ${extension.pkgName}")
        }

        if (extension.type == org.skepsun.kototoro.extensions.repo.ExternalExtensionType.JAR) {
            val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }
            val jarFile = File(pluginsDir, "${extension.pkgName}.jar")
            outputFile.copyTo(jarFile, overwrite = true)
            outputFile.delete()
            context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
                .edit()
                .putLong(extension.pkgName, extension.versionCode)
                .putString("${extension.pkgName}:repo", extension.repoUrl)
                .putString("${extension.pkgName}:repoName", extension.repoName)
                .apply()
            org.skepsun.kototoro.core.extensions.GlobalExtensionManager.initialize(context)
            return ExtensionInstallResult.Completed
        }

        if (extension.type == ExternalExtensionType.CLOUDSTREAM) {
            val compatibility = CloudstreamPluginCompatibilityChecker.inspect(outputFile, context.classLoader)
            val incompatibility = compatibility as? CloudstreamPluginCompatibility.Incompatible
            if (incompatibility != null && !incompatibility.isLinkageGap) {
                outputFile.delete()
                throw IncompatiblePluginException(
                    name = extension.name,
                    cause = IllegalStateException(incompatibility.reason),
                )
            }
            // A plugin linking against host API Kototoro lacks still loads on Android, as in Cloudstream; only its
            // load decides. Should it fail, the missing classes are the likelier explanation than its stack trace.
            try {
                cloudstreamRuntimeManager.installPlugin(outputFile, extension.archiveName, extension.pkgName)
            } catch (error: Throwable) {
                val missingClasses = incompatibility?.missingHostClasses.orEmpty()
                when {
                    error is CancellationException -> throw error
                    missingClasses.isNotEmpty() -> throw MissingPluginHostClassesException(
                        pluginName = extension.name,
                        hostName = "Cloudstream",
                        missingClassNames = missingClasses,
                    ).apply { initCause(error) }
                    // A plugin's load can end in a LinkageError; report it as an incompatible plugin, not a crash.
                    error is LinkageError -> throw IncompatiblePluginException(name = extension.name, cause = error)
                    else -> throw error
                }
            } finally {
                outputFile.delete()
            }
            context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
                .edit()
                .putLong(extension.pkgName, extension.versionCode)
                .putString("${extension.pkgName}:name", extension.name)
                .putString("${extension.pkgName}:lang", extension.lang)
                .putString("${extension.pkgName}:repo", extension.repoUrl)
                .putString("${extension.pkgName}:repoName", extension.repoName)
                .putString("${extension.pkgName}:archive", extension.archiveName)
                .putString("${extension.pkgName}:icon", extension.iconUrl)
                .apply()
            return ExtensionInstallResult.Completed
        }

        val ecosystem = extension.type.toLocalApkEcosystem()
        if (ecosystem != null) {
            recordPackageOrigin(extension)
            if (mode == ExtensionInstallMode.LOCAL_APK) {
                LocalApkExtensionSupport.storeManagedApk(
                    context = context,
                    ecosystem = ecosystem,
                    packageName = extension.pkgName,
                    sourceFile = outputFile,
                )
                outputFile.delete()
                return ExtensionInstallResult.Completed
            }
        }

        val installSession = systemPackageInstaller.createSession(
            apkFile = outputFile,
            expectedPackageName = extension.type.toInstalledPackageName(extension.pkgName),
            expectedVersionCode = extension.versionCode,
        )
        return ExtensionInstallResult.RequiresInstaller(installSession)
    }

    /**
     * Remembers which repository a MIHON / ANIYOMI / IREADER / TSUNDOKU package came from (JAR and Cloudstream keep it
     * in their version prefs), so a saved source group keeps the package's repoUrl. The row is keyed per package,
     * not per source, and never touches the per-source origin rows.
     */
    private suspend fun recordPackageOrigin(extension: RepoAvailableExtension) {
        val installedName = extension.type.toInstalledPackageName(extension.pkgName)
        val now = System.currentTimeMillis()
        try {
            sourceOriginsDao.upsert(
                org.skepsun.kototoro.core.db.entity.SourceOriginEntity(
                    sourceKey = "PKG_${extension.type.name}_$installedName",
                    kind = extension.type.name,
                    displayName = extension.name,
                    packageName = installedName,
                    repositoryUrl = extension.repoUrl,
                    repositoryName = extension.repoName,
                    versionName = extension.versionName,
                    versionCode = extension.versionCode,
                    lastSeenAt = now,
                    updatedAt = now,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("KototoroInit", "recording package origin failed for ${extension.pkgName}", e)
        }
    }

    fun onInstallerActivityReturned() {
        systemPackageInstaller.onInstallerActivityReturned()
    }

    fun cancelDownload(packageName: String) {
        activeCalls[packageName]?.cancel()
    }

    private val lastProgressEmitNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun updateDownloadState(packageName: String, bytesRead: Long, contentLength: Long, done: Boolean = false) {
        val now = System.nanoTime()
        val last = lastProgressEmitNanos[packageName]
        if (!done && last != null && now - last < 250_000_000L) return
        lastProgressEmitNanos[packageName] = now
        _downloadStates.update { states ->
            states + (packageName to ExtensionInstallDownloadState(packageName, bytesRead, contentLength))
        }
    }

    private inner class ExtensionInstallProgressListener(
        private val packageName: String,
    ) : eu.kanade.tachiyomi.network.ProgressListener {

        override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
            updateDownloadState(packageName, bytesRead, contentLength, done)
        }
    }
}

sealed interface ExtensionInstallResult {
    data object Completed : ExtensionInstallResult
    data class RequiresInstaller(val session: SystemPackageInstallSession) : ExtensionInstallResult
}

enum class ExtensionInstallMode {
    LOCAL_APK,
    SYSTEM,
}

private fun ExternalExtensionType.toLocalApkEcosystem(): String? {
    return when (this) {
        ExternalExtensionType.MIHON -> "mihon"
        ExternalExtensionType.ANIYOMI -> "aniyomi"
        ExternalExtensionType.IREADER -> "ireader"
        ExternalExtensionType.TSUNDOKU -> "tsundoku"
        ExternalExtensionType.JAR -> null
        ExternalExtensionType.CLOUDSTREAM -> null
    }
}

data class ExtensionInstallDownloadState(
    val packageName: String,
    val bytesRead: Long,
    val contentLength: Long,
) {

    val progressPercent: Int?
        get() = if (contentLength <= 0L) {
            null
        } else {
            ((bytesRead * 100L) / contentLength)
                .coerceIn(0L, 100L)
                .toInt()
        }
}
