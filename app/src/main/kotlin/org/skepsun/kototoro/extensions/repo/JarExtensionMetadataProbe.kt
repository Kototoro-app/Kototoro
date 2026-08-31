package org.skepsun.kototoro.extensions.repo

import android.content.Context
import dalvik.system.DexFile
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog

/**
 * Resolves JAR source languages without installing the extension or loading its classes.
 *
 * Repository JARs are cached, then only their DEX class names are enumerated. Supported parser
 * architectures encode source languages in stable `site.<language>` package segments.
 */
@Singleton
class JarExtensionMetadataProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
    private val mirrorRepository: GitHubMirrorCatalogRepository,
) {

    private val cacheMutex = Mutex()

    private val githubHttpClient by lazy {
        httpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    suspend fun resolve(extension: RepoAvailableExtension): RepoAvailableExtension {
        if (extension.type != ExternalExtensionType.JAR) {
            return extension
        }
        return withContext(Dispatchers.IO) {
            cacheMutex.lock()
            try {
                val archive = cachedArchive(extension)
                archive.setReadOnly()
                val dexFile = DexFile(archive.absolutePath)
                try {
                    val metadata = JarExtensionLanguageInspector.fromClassNames(dexFile.entries().asSequence())
                    extension.copy(
                        languageCodes = metadata.languageCodes,
                        includesUniversalLanguage = metadata.includesUniversalLanguage,
                        isLanguageMetadataKnown = metadata.isKnown,
                    )
                } finally {
                    dexFile.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                extension
            } finally {
                cacheMutex.unlock()
            }
        }
    }

    private suspend fun cachedArchive(extension: RepoAvailableExtension): File {
        val cacheDir = File(context.cacheDir, "extension-metadata").apply { mkdirs() }
        val target = File(cacheDir, "${extension.pkgName}-${extension.versionCode}.jar")
        if (target.isFile && target.length() > 0L) {
            return target
        }
        val temporary = File(cacheDir, "${extension.pkgName}-${extension.versionCode}.download")
        val rawUrl = extension.archiveUrl
            ?: "${extension.repoUrl.trimEnd('/')}/apk/${extension.archiveName}"
        val entry = mirrorRepository.entry(settings.gitHubMirrorId) ?: GitHubMirrorCatalog.NATIVE
        // Same fallback chain as the installer: jsDelivr-style mirrors cannot serve
        // GitHub release assets, so other mirrors are tried before giving up.
        var lastError: Exception? = null
        for (url in gitHubArchiveCandidates(rawUrl, entry, mirrorRepository.entries.value)) {
            val client = if (url.isGitHubUrl()) githubHttpClient else httpClient
            try {
                client.newCall(GET(url)).awaitSuccess().use { response ->
                    val body = response.body
                    temporary.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                }
                return target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                temporary.delete()
            }
        }
        throw lastError ?: java.io.IOException("No download candidate succeeded for ${extension.pkgName}")
    }

    private fun String.isGitHubUrl(): Boolean {
        return startsWith("https://github.com/") ||
            startsWith("https://api.github.com/") ||
            contains(".githubusercontent.com/")
    }
}

internal object JarExtensionLanguageInspector {

    private val sitePrefixes = listOf(
        "org.skepsun.kototoro.parsers.site.",
        "org.koitharu.kotatsu.parsers.site.",
        "tsuki.site.",
    )

    fun fromClassNames(classNames: Sequence<String>): JarExtensionLanguageMetadata {
        val languages = sortedSetOf<String>()
        var includesUniversalLanguage = false
        var isKnown = false
        classNames.forEach { className ->
            val prefix = sitePrefixes.firstOrNull(className::startsWith) ?: return@forEach
            val language = className.removePrefix(prefix)
                .substringBefore('.')
                .replace('_', '-')
                .lowercase(Locale.ROOT)
            when {
                language == "all" -> {
                    includesUniversalLanguage = true
                    isKnown = true
                }
                language.matches(LANGUAGE_TAG) -> {
                    languages += language
                    isKnown = true
                }
            }
        }
        return JarExtensionLanguageMetadata(languages, includesUniversalLanguage, isKnown)
    }

    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")
}

internal data class JarExtensionLanguageMetadata(
    val languageCodes: Set<String>,
    val includesUniversalLanguage: Boolean,
    val isKnown: Boolean,
)
