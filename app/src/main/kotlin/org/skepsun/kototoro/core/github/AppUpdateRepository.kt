package org.skepsun.kototoro.core.github

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.os.AppValidator
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.suspendlazy.getOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"
private const val BUILD_TYPE_RELEASE = "release"
private const val ABI_ARM64_V8A = "arm64-v8a"
private const val ABI_ARMEABI_V7A = "armeabi-v7a"
private const val ABI_X86_64 = "x86_64"
private const val ABI_X86 = "x86"
private const val ABI_UNIVERSAL = "universal"
private const val UPDATE_REQUEST_TIMEOUT_SECONDS = 12L

data class AppUpdateSourceProbe(
    val latencyMillis: Long?,
    val isAvailable: Boolean,
)

@Singleton
class AppUpdateRepository @Inject constructor(
    private val appValidator: AppValidator,
    private val settings: AppSettings,
    @BaseHttpClient okHttp: OkHttpClient,
    @ApplicationContext context: Context,
) {

    private val availableUpdate = MutableStateFlow<AppVersion?>(null)
    private val githubRepository = context.getString(R.string.github_updates_repo)
    private val gitcodeRepository = context.getString(R.string.gitcode_updates_repo)
    private val primaryLocale = context.resources.configuration.locales[0]
    private val updateHttp = okHttp.newBuilder()
        .connectTimeout(UPDATE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(UPDATE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(UPDATE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val defaultSource: AppUpdateSource
        get() = settings.appUpdateSource ?: preferredUpdateSource(primaryLocale)

    val isUpdateAvailable: Boolean
        get() = availableUpdate.value != null

    fun observeAvailableUpdate() = availableUpdate.asStateFlow()

    suspend fun getAvailableVersions(
        source: AppUpdateSource = defaultSource,
    ): List<AppVersion> = fetchAvailableVersions(source)

    suspend fun probeUpdateSources(): Map<AppUpdateSource, AppUpdateSourceProbe> = coroutineScope {
        AppUpdateSource.entries.map { source ->
            async {
                val startedAt = SystemClock.elapsedRealtime()
                val available = try {
                    fetchAvailableVersions(source).isNotEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                val latency = if (available) SystemClock.elapsedRealtime() - startedAt else null
                source to AppUpdateSourceProbe(latencyMillis = latency, isAvailable = available)
            }
        }.awaitAll().toMap()
    }

    private suspend fun fetchAvailableVersions(source: AppUpdateSource): List<AppVersion> {
        val repository = repositoryFor(source)
        val releasesUrl = when (source) {
            AppUpdateSource.GITHUB -> "https://api.github.com/repos/$repository/releases?page=1&per_page=10"
            AppUpdateSource.GITCODE -> "https://api.gitcode.com/api/v5/repos/$repository/releases?page=1&per_page=10"
        }
        val request = Request.Builder().get().url(releasesUrl).build()
        val releases = updateHttp.newCall(request).await().use { response ->
            if (response.code == 403) {
                val body = response.body.string()
                if (body.contains("API rate limit exceeded", ignoreCase = true)) {
                    throw TooManyRequestExceptions(releasesUrl, 0L)
                }
                throw IllegalStateException("HTTP error 403")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP error ${response.code}")
            }
            response.parseJsonArray()
        }
        return parseUpdateReleases(releases, source, repository, Build.SUPPORTED_ABIS.asList())
    }

    suspend fun fetchUpdate(
        source: AppUpdateSource = defaultSource,
    ): AppVersion? = withContext(Dispatchers.Default) {
        availableUpdate.value = null
        if (!isUpdateSupported()) {
            return@withContext null
        }
        runCatchingCancellable {
            val currentVersion = parseVersionId(BuildConfig.VERSION_NAME)
            val available = getAvailableVersions(source).toMutableList()
            available.sortBy { it.versionId }
            if (currentVersion.variantType.isEmpty() && !settings.isUnstableUpdatesAllowed) {
                available.retainAll { it.versionId.variantType.isEmpty() }
            }

            val latest = available.maxByOrNull { it.versionId }?.takeIf { it.versionId > currentVersion }
            if (latest != null && latest.patchUrl != null) {
                // Incremental patches are generated only against the immediately preceding release.
                val sorted = available.sortedByDescending { it.versionId }
                val previousReleaseIndex = sorted.indexOf(latest) + 1
                val isImmediatePrecursor = previousReleaseIndex < sorted.size &&
                    currentVersion == sorted[previousReleaseIndex].versionId
                if (!isImmediatePrecursor) {
                    // Fall back to the full APK when the installed version skipped a release.
                    return@runCatchingCancellable latest.copy(patchUrl = null, patchSize = null)
                }
            }
            latest
        }.onFailure {
            it.printStackTrace()
        }.onSuccess {
            availableUpdate.value = it
        }.getOrNull()
    }

    @Suppress("KotlinConstantConditions")
    suspend fun isUpdateSupported(): Boolean {
        return BuildConfig.BUILD_TYPE != BUILD_TYPE_RELEASE || appValidator.isOriginalApp.getOrNull() == true
    }

    private fun repositoryFor(source: AppUpdateSource): String = when (source) {
        AppUpdateSource.GITHUB -> githubRepository
        AppUpdateSource.GITCODE -> gitcodeRepository
    }
}

internal fun preferredUpdateSource(locale: Locale): AppUpdateSource {
    val prefersGitCode = locale.country.equals("CN", ignoreCase = true) ||
        (locale.language.equals("zh", ignoreCase = true) && locale.script.equals("Hans", ignoreCase = true))
    return if (prefersGitCode) AppUpdateSource.GITCODE else AppUpdateSource.GITHUB
}

internal fun parseUpdateReleases(
    jsonArray: JSONArray,
    source: AppUpdateSource,
    repository: String,
    supportedAbis: List<String>,
): List<AppVersion> = jsonArray.mapJSONNotNull { json ->
    val assets = json.optJSONArray("assets")?.toAssetList().orEmpty()
    val apkAssets = assets.filter { it.isApkAsset() }
    if (apkAssets.isEmpty()) {
        return@mapJSONNotNull null
    }
    val apkAsset = apkAssets.findBestAsset(supportedAbis) ?: apkAssets.first()
    val patchAsset = assets.filter { it.isPatchAsset() }.findBestAsset(supportedAbis)
    val apkUrl = apkAsset.optString("browser_download_url").takeIf { it.isNotBlank() }
        ?: return@mapJSONNotNull null
    val tagName = json.optString("tag_name")
    val versionName = when (source) {
        AppUpdateSource.GITHUB -> json.optString("name").ifBlank { tagName }.removePrefix("v")
        AppUpdateSource.GITCODE -> tagName.toVersionName()
    }
    val releaseUrl = when (source) {
        AppUpdateSource.GITHUB -> json.optString("html_url")
        AppUpdateSource.GITCODE -> "https://gitcode.com/$repository/releases/$tagName"
    }
    AppVersion(
        id = json.optLong("id", tagName.hashCode().toLong() and 0xffffffffL),
        url = releaseUrl,
        name = versionName,
        apkSize = apkAsset.optLong("size", 0L),
        apkUrl = apkUrl,
        patchSize = patchAsset?.optLong("size", 0L),
        patchUrl = patchAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
        description = json.optString("body"),
        source = source,
    )
}

private fun parseVersionId(versionName: String): VersionId {
    val normalized = versionName.trim()
    if (normalized.startsWith('n', ignoreCase = true)) {
        val nightlyBuild = normalized.filter { it.isDigit() }.toIntOrNull() ?: 0
        return VersionId(0, 0, nightlyBuild, "n", 0)
    }
    val parts = normalized.substringBeforeLast('-').split('.')
    val variant = normalized.substringAfterLast('-', "")
    return VersionId(
        major = parts.getOrNull(0)?.toIntOrNull() ?: 0,
        minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        build = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        variantType = variant.filter(Char::isLetter),
        variantNumber = variant.filter(Char::isDigit).toIntOrNull() ?: 0,
    )
}

private fun String.toVersionName(): String {
    return if (startsWith("nightly-", ignoreCase = true)) {
        "N${filter(Char::isDigit)}"
    } else {
        removePrefix("v")
    }
}

private fun JSONArray.toAssetList(): List<JSONObject> {
    val assetList = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val item = getJSONObject(i)
        if (item.isApkAsset() || item.isPatchAsset()) {
            assetList += item
        }
    }
    return assetList
}

private fun JSONObject.isApkAsset(): Boolean {
    return optString("content_type") == CONTENT_TYPE_APK ||
        optString("name").endsWith(".apk", ignoreCase = true)
}

private fun JSONObject.isPatchAsset(): Boolean {
    return optString("name").endsWith(".patch", ignoreCase = true)
}

private fun List<JSONObject>.findBestAsset(supportedAbis: List<String>): JSONObject? {
    val normalizedAbis = supportedAbis.mapNotNull { it.normalizeAbi() }.distinct()
    for (abi in normalizedAbis) {
        firstOrNull { it.detectAssetAbi() == abi }?.let { return it }
    }
    return firstOrNull { it.detectAssetAbi() == ABI_UNIVERSAL }
        ?: firstOrNull { it.detectAssetAbi() == null }
}

private fun String.normalizeAbi(): String? = when (lowercase()) {
    ABI_ARM64_V8A -> ABI_ARM64_V8A
    ABI_ARMEABI_V7A -> ABI_ARMEABI_V7A
    ABI_X86_64 -> ABI_X86_64
    ABI_X86 -> ABI_X86
    else -> null
}

private fun JSONObject.detectAssetAbi(): String? {
    val name = optString("name").lowercase()
    return when {
        "-$ABI_ARM64_V8A-" in name -> ABI_ARM64_V8A
        "-$ABI_ARMEABI_V7A-" in name -> ABI_ARMEABI_V7A
        "-$ABI_X86_64-" in name -> ABI_X86_64
        "-$ABI_X86-" in name -> ABI_X86
        "-$ABI_UNIVERSAL-" in name -> ABI_UNIVERSAL
        else -> null
    }
}
