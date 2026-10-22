package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the release packages are published; the nightly channel keeps its own repository, as on Android. */
const val DESKTOP_UPDATE_REPOSITORY = "Kototoro-app/Kototoro"
const val DESKTOP_NIGHTLY_REPOSITORY = "Kototoro-app/Kototoro-Nightly"

fun desktopUpdateRepository(channel: DesktopUpdateChannel): String = when (channel) {
    DesktopUpdateChannel.RELEASE -> DESKTOP_UPDATE_REPOSITORY
    DesktopUpdateChannel.NIGHTLY -> DESKTOP_NIGHTLY_REPOSITORY
}

/** Windows counterparts of Android's app-update settings. */
data class DesktopUpdateSettings(
    val autoCheck: Boolean = true,
    /** Downloads in the background once a check finds something; installing still restarts the app. */
    val autoDownload: Boolean = false,
    /** Applies an already downloaded package when the window closes instead of waiting for the button. */
    val installOnExit: Boolean = false,
) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_AUTO_CHECK to SourcePreferenceValue.Toggle(autoCheck),
        KEY_AUTO_DOWNLOAD to SourcePreferenceValue.Toggle(autoDownload),
        KEY_INSTALL_ON_EXIT to SourcePreferenceValue.Toggle(installOnExit),
    )

    companion object {
        const val KEY_AUTO_CHECK = "app_update_auto_check"
        const val KEY_AUTO_DOWNLOAD = "app_update_auto_download"
        const val KEY_INSTALL_ON_EXIT = "app_update_install_on_exit"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopUpdateSettings(
            autoCheck = (values[KEY_AUTO_CHECK] as? SourcePreferenceValue.Toggle)?.value ?: true,
            autoDownload = (values[KEY_AUTO_DOWNLOAD] as? SourcePreferenceValue.Toggle)?.value ?: false,
            installOnExit = (values[KEY_INSTALL_ON_EXIT] as? SourcePreferenceValue.Toggle)?.value ?: false,
        )
    }
}

/** Progress of a package download, for the window's progress line; [total] is 0 when no length was announced. */
fun interface DesktopUpdateProgress {
    fun onProgress(downloaded: Long, total: Long)
}

/** A verified package on disk, ready to be applied at the next restart. */
data class DesktopDownloadedUpdate(
    val release: DesktopUpdateRelease,
    val packageKind: DesktopUpdatePackage,
    val file: Path,
    val sha256: String,
)

class DesktopUpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Checks the published GitHub releases for a Windows package newer than the installed build, and downloads it.
 *
 * The rules are the Android client's, narrowed to what a desktop package needs:
 * * only this channel's assets (`…-windows-x64-release` / `…-windows-x64-nightly`), never a foreign artifact;
 * * the highest release strictly newer than the running version wins; a stable build is never offered a pre-release;
 * * a package is only accepted after its size and SHA-256 match the release's published `.sha256` file, and its
 *   container is actually a Kototoro image.
 *
 * Everything except the HTTP calls is pure, so the selection rules are tested with recorded release JSON.
 */
class DesktopAppUpdateClient(
    private val currentVersion: DesktopPackageVersion,
    private val channel: DesktopUpdateChannel,
    private val repository: String = desktopUpdateRepository(channel),
    private val http: HttpClient = defaultHttpClient(),
) {
    /** The newest installable release, or null when the running version is up to date (or nothing is published). */
    suspend fun check(): DesktopAppUpdate? {
        val best = selectUpdate(usableReleases(), currentVersion, channel) ?: return null
        return DesktopAppUpdate(
            current = currentVersion,
            available = best,
            isNewer = true,
            notes = best.notes,
            sizeBytes = best.packages.values.maxOfOrNull { it.sizeBytes } ?: 0L,
        )
    }

    /**
     * The newest release this channel publishes that could be installed, whether or not this copy already has it.
     *
     * [check] answers "is there something to do"; this answers "what is out there", which is what a diagnostic needs
     * when the honest answer is that the published build is not newer than the installed one.
     */
    suspend fun newest(): DesktopUpdateRelease? = newestRelease(usableReleases(), channel)

    /**
     * The releases of this channel that can actually be installed: a package with a download URL, and — once
     * published — a `.sha256` file, since a package that cannot be verified is never offered.
     */
    private suspend fun usableReleases(): List<DesktopUpdateRelease> = withContext(Dispatchers.IO) {
        val body = String(readBytes(URI("https://api.github.com/repos/$repository/releases?per_page=$RELEASES_PER_PAGE"),
            MAXIMUM_RELEASES_BYTES), Charsets.UTF_8)
        parseReleases(body, channel).filter { it.checksumsUrl != null }
    }

    /** The release's published checksums, keyed by asset file name. */
    private suspend fun checksums(release: DesktopUpdateRelease): Map<String, String> {
        val url = release.checksumsUrl ?: throw DesktopUpdateException(
            DesktopRuntimeStrings.text("runtime.update.checksumMissing", "the release publishes no SHA-256 file"))
        val text = withContext(Dispatchers.IO) {
            String(readBytes(URI(url), MAXIMUM_CHECKSUM_BYTES), Charsets.UTF_8)
        }
        return parseChecksums(text)
    }

    /**
     * Downloads [packageKind] of [update] into [directory] and verifies it. A failed or cancelled attempt leaves no
     * file behind, so the next one starts clean instead of resuming a corrupt partial.
     */
    suspend fun download(
        update: DesktopAppUpdate,
        packageKind: DesktopUpdatePackage,
        directory: Path,
        onProgress: DesktopUpdateProgress = DesktopUpdateProgress { _, _ -> },
    ): DesktopDownloadedUpdate = withContext(Dispatchers.IO) {
        val release = update.available
        val asset = release.packageOf(packageKind) ?: throw DesktopUpdateException(DesktopRuntimeStrings.text(
            "runtime.update.packageMissing", "this release has no %s package", packageKind.name.lowercase(Locale.ROOT)))
        val expected = checksums(release)[asset.name] ?: throw DesktopUpdateException(DesktopRuntimeStrings.text(
            "runtime.update.checksumMissingFor", "the SHA-256 file does not list %s", asset.name))
        Files.createDirectories(directory)
        val partial = directory.resolve("${asset.name}.part")
        val target = directory.resolve(asset.name)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(partial).use { output ->
                streamBytes(URI(asset.downloadUrl), onProgress) { buffer, length ->
                    digest.update(buffer, 0, length)
                    output.write(buffer, 0, length)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw DesktopUpdateException(DesktopRuntimeStrings.text(
                "runtime.update.checksumMismatch", "the downloaded package does not match %s", expected))
            if (asset.sizeBytes > 0) require(Files.size(partial) == asset.sizeBytes) {
                DesktopRuntimeStrings.text("runtime.update.sizeMismatch", "the downloaded package has the wrong size")
            }
            verifyContainer(partial, packageKind)
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            DesktopDownloadedUpdate(release, packageKind, target, actual)
        } catch (error: Throwable) {
            Files.deleteIfExists(partial)
            if (error is CancellationException) throw error
            throw error
        }
    }

    /** A ZIP must carry the application image the installer overwrites; an MSI must have the compound-file header. */
    private fun verifyContainer(file: Path, kind: DesktopUpdatePackage) {
        val image = when (kind) {
            DesktopUpdatePackage.PORTABLE_ZIP -> Files.newInputStream(file).use { input ->
                ZipInputStream(input).use { zip ->
                    var found = false
                    while (!found) {
                        val entry = zip.nextEntry ?: break
                        found = IMAGE_ENTRY.matches(entry.name)
                    }
                    found
                }
            }
            // Every MSI is a compound file; a truncated or HTML-error download fails this check.
            DesktopUpdatePackage.MSI -> Files.newInputStream(file).use { it.readNBytes(MSI_SIGNATURE.size) }
                .contentEquals(MSI_SIGNATURE)
        }
        if (!image) throw DesktopUpdateException(
            DesktopRuntimeStrings.text("runtime.update.notAnImage", "the package is not a Kototoro image"))
    }

    private suspend fun readBytes(uri: URI, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET().build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
        if (response.statusCode() !in 200..299) throw DesktopUpdateException(DesktopRuntimeStrings.text(
            "runtime.update.http", "the update server answered HTTP %d", response.statusCode()))
        require(response.body().size <= limit) {
            DesktopRuntimeStrings.text("runtime.update.tooLarge", "the update data is too large")
        }
        response.body()
    }

    private suspend fun streamBytes(uri: URI, onProgress: DesktopUpdateProgress, consume: (ByteArray, Int) -> Unit) {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
            .header("User-Agent", USER_AGENT)
            .GET().build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw DesktopUpdateException(DesktopRuntimeStrings.text(
                "runtime.update.http", "the update server answered HTTP %d", response.statusCode()))
        }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(0L)
        response.body().use { input -> copy(input, total, onProgress, consume) }
    }

    private fun copy(
        input: InputStream,
        total: Long,
        onProgress: DesktopUpdateProgress,
        consume: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var downloaded = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            consume(buffer, read)
            downloaded += read
            // Report at most every 256 KiB: a progress callback per 64 KiB chunk would re-compose the window constantly.
            if (downloaded - lastReport >= PROGRESS_STEP_BYTES || (total > 0 && downloaded >= total)) {
                lastReport = downloaded
                onProgress.onProgress(downloaded, total)
            }
        }
    }

    private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, error -> continuation.completeWith(value, error?.cause ?: error) }
        continuation.invokeOnCancellation { cancel(true) }
    }

    private fun <T> CancellableContinuation<T>.completeWith(value: T, error: Throwable?) {
        if (error != null) resumeWithException(error) else resume(value)
    }

    private companion object {
        const val RELEASES_PER_PAGE = 10
        const val MAXIMUM_RELEASES_BYTES = 4 * 1024 * 1024
        const val MAXIMUM_CHECKSUM_BYTES = 64 * 1024
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024L
        const val USER_AGENT = "Kototoro-Windows"
        val IMAGE_ENTRY = Regex("(^|.*/)Kototoro\\.exe", RegexOption.IGNORE_CASE)
        val MSI_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/**
 * Parses the GitHub releases payload into the packages this channel can install.
 *
 * Assets are recognised by their published name — `Kototoro-<version>-windows-x64-<channel>.zip|.msi` — and the
 * checksum file by the same stem with `.sha256`. Anything else in a release (APKs, iOS builds, patches, the other
 * channel) is ignored instead of guessed at.
 */
internal fun parseReleases(json: String, channel: DesktopUpdateChannel): List<DesktopUpdateRelease> {
    val releases = try {
        SourceProtocolJson.decodeFromString<List<GithubRelease>>(json)
    } catch (error: Exception) {
        throw DesktopUpdateException(
            DesktopRuntimeStrings.text("runtime.update.unreadable", "the update data could not be read"), error)
    }
    return releases.filterNot { it.draft }.mapNotNull { it.toUpdateRelease(channel) }
}

private fun GithubRelease.toUpdateRelease(channel: DesktopUpdateChannel): DesktopUpdateRelease? {
    val version = versionOfAssets() ?: return null
    val stem = "Kototoro-${version.text}-windows-x64-${channel.assetSegment}"
    val packages = assets.mapNotNull { asset ->
        val kind = when {
            asset.name.endsWith(".zip", ignoreCase = true) -> DesktopUpdatePackage.PORTABLE_ZIP
            asset.name.endsWith(".msi", ignoreCase = true) -> DesktopUpdatePackage.MSI
            else -> return@mapNotNull null
        }
        if (!asset.name.equals(stem + packageSuffix(kind), ignoreCase = true)) return@mapNotNull null
        kind to DesktopUpdateAsset(asset.name, asset.downloadUrl, asset.size, kind)
    }.toMap()
    if (packages.isEmpty()) return null
    return DesktopUpdateRelease(
        version = version,
        tag = tag,
        // GitHub keeps the changelog in the body; the window shows it as written, like the Android update sheet.
        notes = body.trim(),
        publishedAt = publishedAt,
        publishedUrl = htmlUrl,
        packages = packages,
        checksumsUrl = assets.firstOrNull { it.name.equals("$stem.sha256", ignoreCase = true) }?.downloadUrl,
    )
}

private fun packageSuffix(kind: DesktopUpdatePackage) = when (kind) {
    DesktopUpdatePackage.PORTABLE_ZIP -> ".zip"
    DesktopUpdatePackage.MSI -> ".msi"
}

/** The version as the asset name carries it, e.g. `1.9.9`, `1.10.0-beta1` or `N20261022`. */
private fun GithubRelease.versionOfAssets(): DesktopPackageVersion? {
    val candidate = assets.firstNotNullOfOrNull { asset ->
        ASSET_NAME.find(asset.name)?.groupValues?.getOrNull(1)
    } ?: return null
    return DesktopPackageVersion.parse(candidate).takeUnless { it == DesktopPackageVersion.UNKNOWN }
}

private val ASSET_NAME = Regex("^Kototoro-(.+?)-windows-x64-(?:release|nightly)\\.[A-Za-z]+$", RegexOption.IGNORE_CASE)

/**
 * Picks the release to offer: the highest version strictly newer than [current] that carries a package. A stable
 * build is never offered a pre-release, and a nightly installation only ever sees the nightly repository.
 */
internal fun selectUpdate(
    releases: List<DesktopUpdateRelease>,
    current: DesktopPackageVersion,
    channel: DesktopUpdateChannel,
): DesktopUpdateRelease? = newestRelease(releases.filter { it.version > current }, channel)

/** The newest release of [channel] that carries a downloadable package, ignoring what the running copy is. */
internal fun newestRelease(
    releases: List<DesktopUpdateRelease>,
    channel: DesktopUpdateChannel,
): DesktopUpdateRelease? = releases
    .filter { it.packages.isNotEmpty() && it.packages.values.any { asset -> asset.downloadUrl.isNotBlank() } }
    .filter { channel == DesktopUpdateChannel.NIGHTLY || it.version.isStable }
    .maxByOrNull { it.version }

/**
 * Parses a published `.sha256` file: one `<hex>  <name>` line per asset (the `sha256sum` format CI writes). Blank
 * lines and comments are ignored, so a hand-edited file still parses.
 */
internal fun parseChecksums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
    val hash = trimmed.takeWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)
    val name = trimmed.drop(hash.length).trim().removePrefix("*")
    val valid = hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' } && name.isNotEmpty()
    if (valid) name to hash else null
}.toMap()
