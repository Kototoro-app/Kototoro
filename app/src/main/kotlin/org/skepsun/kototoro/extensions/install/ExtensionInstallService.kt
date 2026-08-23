package org.skepsun.kototoro.extensions.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
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
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.repo.toInstalledPackageName
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

import org.skepsun.kototoro.core.prefs.AppSettings

@Singleton
class ExtensionInstallService @Inject constructor(
    @ApplicationContext private val context: Context,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
    private val cloudstreamRuntimeManager: org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager,
    private val systemPackageInstaller: SystemPackageInstaller,
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

    private fun applyMirror(url: String): String {
        if (url.startsWith("https://raw.githubusercontent.com/")) {
            return when (settings.gitHubMirror) {
                AppSettings.GitHubMirror.NATIVE -> url
                AppSettings.GitHubMirror.KKGITHUB -> url.replace("raw.githubusercontent.com", "raw.kkgithub.com")
                AppSettings.GitHubMirror.GHPROXY -> "https://mirror.ghproxy.com/$url"
                AppSettings.GitHubMirror.GHPROXY_NET -> "https://ghproxy.net/$url"
            }
        }
        return url
    }

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val _downloadStates = MutableStateFlow<Map<String, ExtensionInstallDownloadState>>(emptyMap())

    val downloadStates: StateFlow<Map<String, ExtensionInstallDownloadState>> = _downloadStates.asStateFlow()

    suspend fun install(
        extension: RepoAvailableExtension,
        mode: ExtensionInstallMode = ExtensionInstallMode.LOCAL_APK,
    ): ExtensionInstallResult {
        val archiveUrl = extension.archiveUrl?.let(::applyMirror) ?: when (extension.type) {
            ExternalExtensionType.CLOUDSTREAM -> applyMirror("${extension.repoUrl}/${extension.archiveName}")
            else -> applyMirror("${extension.repoUrl}/apk/${extension.archiveName}")
        }
        val outputDir = File(context.cacheDir, "extension-installs").apply { mkdirs() }
        val archiveExtension = extension.archiveName.substringAfterLast('.', missingDelimiterValue = "apk")
        val outputFile = File(outputDir, "${extension.pkgName}-${extension.versionCode}.$archiveExtension")
        val call = downloadClient(archiveUrl)
            .newCachelessCallWithProgress(GET(archiveUrl), ExtensionInstallProgressListener(extension.pkgName))
        check(activeCalls.putIfAbsent(extension.pkgName, call) == null) {
            "Extension install download already in progress for ${extension.pkgName}"
        }
        updateDownloadState(extension.pkgName, bytesRead = 0L, contentLength = -1L)
        try {
            call.awaitSuccess().use { response ->
                val body = requireNotNull(response.body) { "Missing APK response body" }
                outputFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("Extension install download cancelled for ${extension.pkgName}", e)
            }
            throw e
        } finally {
            activeCalls.remove(extension.pkgName)
            _downloadStates.update { it - extension.pkgName }
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
            if (compatibility is CloudstreamPluginCompatibility.Incompatible) {
                outputFile.delete()
                if (compatibility.missingHostClasses.isNotEmpty()) {
                    throw MissingPluginHostClassesException(
                        pluginName = extension.name,
                        hostName = "Cloudstream",
                        missingClassNames = compatibility.missingHostClasses,
                    )
                }
                throw IncompatiblePluginException(
                    name = extension.name,
                    cause = IllegalStateException(compatibility.reason),
                )
            }
            val pluginsDir = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
            val pluginFile = File(pluginsDir, extension.archiveName)
            outputFile.copyTo(pluginFile, overwrite = true)
            outputFile.delete()
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
            cloudstreamRuntimeManager.initialize()
            return ExtensionInstallResult.Completed
        }

        val ecosystem = extension.type.toLocalApkEcosystem()
        if (ecosystem != null) {
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

    fun onInstallerActivityReturned() {
        systemPackageInstaller.onInstallerActivityReturned()
    }

    fun cancelDownload(packageName: String) {
        activeCalls[packageName]?.cancel()
    }

    private fun updateDownloadState(packageName: String, bytesRead: Long, contentLength: Long) {
        _downloadStates.update { states ->
            states + (packageName to ExtensionInstallDownloadState(packageName, bytesRead, contentLength))
        }
    }

    private inner class ExtensionInstallProgressListener(
        private val packageName: String,
    ) : eu.kanade.tachiyomi.network.ProgressListener {

        override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
            updateDownloadState(packageName, bytesRead, contentLength)
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
