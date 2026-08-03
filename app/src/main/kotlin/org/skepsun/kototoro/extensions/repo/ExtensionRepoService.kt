package org.skepsun.kototoro.extensions.repo

import android.util.Log
import androidx.annotation.Keep
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.BufferedSource
import okio.buffer
import okio.gzip
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.mihon.MihonExtensionLoader
import org.skepsun.kototoro.aniyomi.AniyomiExtensionLoader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ExtensionRepoService @Inject constructor(
	@ContentHttpClient private val httpClient: OkHttpClient,
	private val json: Json,
	private val settings: AppSettings,
) {
	private val githubHttpClient by lazy {
		httpClient.newBuilder()
			.protocols(listOf(Protocol.HTTP_1_1))
			.build()
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

	private fun deriveRepoName(baseUrl: String, defaultName: String): String {
		val url = baseUrl.toHttpUrlOrNull() ?: return defaultName
		val segments = url.pathSegments.filter { it.isNotEmpty() }
		if (segments.size >= 2 && url.host.contains("githubusercontent.com")) {
			return "${segments[0]}/${segments[1]}"
		} else if (segments.size >= 2 && url.host == "github.com") {
			return "${segments[0]}/${segments[1]}"
		} else if (segments.isNotEmpty()) {
			return segments.last()
		}
		return url.host
	}

	private fun isCloudstreamIndexEntry(segment: String?): Boolean {
		val value = segment?.lowercase().orEmpty()
		return value == "plugins.json" ||
			value == "repo.json" ||
			value == "repo" ||
			value.endsWith(".json")
	}

	suspend fun fetchRepoDetails(baseUrl: String, type: ExternalExtensionType): ExternalExtensionRepo {
		if (type == ExternalExtensionType.JAR) {
			parseGitHubRepositoryUrl(baseUrl)?.let { githubRepo ->
				val release = fetchLatestGitHubRelease(githubRepo)
				val now = System.currentTimeMillis()
				return ExternalExtensionRepo(
					type = type,
					baseUrl = githubRepo.webUrl,
					name = "JAR: ${githubRepo.owner}/${githubRepo.repo}",
					shortName = githubRepo.repo,
					website = githubRepo.webUrl,
					signingKeyFingerprint = githubRepo.webUrl.hashCode().toString(16),
					createdAt = now,
					updatedAt = now,
					lastSuccessAt = now,
					lastError = null,
					version = release.tagName,
				)
			}
		}
		if (type == ExternalExtensionType.CLOUDSTREAM) {
			val normalizedInputUrl = baseUrl.toHttpUrlOrNull() ?: error("Invalid Cloudstream repository URL: $baseUrl")
			val (metadataUrl, body) = withTimeout(REPO_DETAILS_TIMEOUT_MS) {
				fetchCloudstreamRepoMetadata(normalizedInputUrl)
			}
			val dto = json.decodeFromString<CloudstreamRepositoryMetaDto>(body)
			val now = System.currentTimeMillis()
			val derived = deriveRepoName(metadataUrl, "Cloudstream")
			return ExternalExtensionRepo(
				type = type,
				baseUrl = metadataUrl,
				name = dto.name.ifBlank { "Cloudstream: $derived" },
				shortName = dto.name.takeIf { it.isNotBlank() } ?: derived,
				website = dto.repositoryUrl ?: dto.website ?: metadataUrl,
				signingKeyFingerprint = cloudstreamRepositoryIdentityUrl(metadataUrl).hashCode().toString(16),
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
				version = dto.manifestVersion?.toString(),
			)
		}

		if (type == ExternalExtensionType.IREADER || type == ExternalExtensionType.JAR) {
			val now = System.currentTimeMillis()
			val fallbackName = when (type) {
				ExternalExtensionType.IREADER -> "IReader"
				ExternalExtensionType.JAR -> "Kototoro"
				ExternalExtensionType.CLOUDSTREAM -> error("Cloudstream handled separately")
				else -> error("Unsupported local metadata extension type: $type")
			}
			val derived = deriveRepoName(baseUrl, fallbackName)
			val repoName = when (type) {
				ExternalExtensionType.IREADER -> "IReader: $derived"
				ExternalExtensionType.JAR -> "Kototoro: $derived"
				ExternalExtensionType.CLOUDSTREAM -> error("Cloudstream handled separately")
				else -> error("Unsupported local metadata extension type: $type")
			}
			val repoShort = derived
			var version: String? = null
			if (type == ExternalExtensionType.JAR) {
				val indexUrl = applyMirror("$baseUrl/index.min.json")
				runCatching {
					withTimeout(REPO_DETAILS_TIMEOUT_MS) {
						val body = httpClient.newCall(GET(indexUrl)).awaitSuccess().use { response ->
							response.body.string()
						}
						val dto = json.decodeFromString<List<ExtensionIndexDto>>(body)
						version = dto.firstOrNull()?.version
					}
				}
			}

			return ExternalExtensionRepo(
				type = type,
				baseUrl = baseUrl,
				name = repoName,
				shortName = repoShort,
				website = baseUrl,
				signingKeyFingerprint = baseUrl.hashCode().toString(16), // Use baseUrl hash as pseudo-fingerprint for JAR/IReader
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
				version = version,
			)
		}

		if (isProtobufIndexUrl(baseUrl)) {
			val index = withTimeout(REPO_DETAILS_TIMEOUT_MS) {
				fetchMihonExtensionStoreIndex(baseUrl)
			}
			val now = System.currentTimeMillis()
			return ExternalExtensionRepo(
				type = type,
				baseUrl = baseUrl,
				name = index.name,
				shortName = index.badgeLabel,
				website = index.contact.website,
				signingKeyFingerprint = index.signingKey,
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
			)
		}

		val repoJsonUrl = applyMirror("$baseUrl/repo.json")
		val startedAt = System.currentTimeMillis()
		Log.d(TAG, "fetchRepoDetails:start type=$type url=$repoJsonUrl")
		return withTimeout(REPO_DETAILS_TIMEOUT_MS) {
			val body = runCatching {
				httpClient.newCall(GET(repoJsonUrl)).awaitSuccess().use { response ->
					response.body.string()
				}
			}.getOrElse { error ->
				if (type == ExternalExtensionType.MIHON || type == ExternalExtensionType.ANIYOMI) {
					return@withTimeout fetchRepoDetails("$baseUrl/index.pb", type)
				}
				throw error
			}
			val dto = json.decodeFromString<RepoMetaWrapperDto>(body)
			dto.indexV2?.let { indexV2 ->
				val resolvedIndexUrl = repoJsonUrl.toHttpUrlOrNull()
					?.resolve(indexV2)
					?.toString()
					?: indexV2
				return@withTimeout fetchRepoDetails(resolvedIndexUrl, type)
			}
			val now = System.currentTimeMillis()
			ExternalExtensionRepo(
				type = type,
				baseUrl = baseUrl,
				name = dto.meta.name,
				shortName = dto.meta.shortName,
				website = dto.meta.website,
				signingKeyFingerprint = dto.meta.signingKeyFingerprint,
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
			)
		}.also { repo ->
			Log.d(
				TAG,
				"fetchRepoDetails:success type=$type baseUrl=${repo.baseUrl} name=${repo.displayName} elapsedMs=${System.currentTimeMillis() - startedAt}",
			)
		}
	}

	suspend fun fetchAvailableExtensions(repo: ExternalExtensionRepo): List<RepoAvailableExtension> {
		return runCatching { fetchAvailableExtensionsOrThrow(repo) }
			.getOrDefault(emptyList())
	}

	suspend fun fetchAvailableExtensionsOrThrow(repo: ExternalExtensionRepo): List<RepoAvailableExtension> {
		if (repo.type == ExternalExtensionType.JAR) {
			parseGitHubRepositoryUrl(repo.baseUrl)?.let { githubRepo ->
				val release = fetchLatestGitHubRelease(githubRepo)
				return release.assets
					.asSequence()
					.filter { it.name.endsWith(".jar", ignoreCase = true) }
					.map { asset -> asset.toAvailableExtension(repo, release, githubRepo) }
					.toList()
					.ifEmpty { error("Latest GitHub release contains no JAR assets: ${githubRepo.webUrl}") }
			}
		}
		if (isProtobufIndexUrl(repo.baseUrl)) {
			return fetchProtobufExtensions(repo)
		}
		val indexUrls = if (repo.type == ExternalExtensionType.CLOUDSTREAM) {
			fetchCloudstreamPluginListUrls(repo)
		} else {
			listOf("${repo.baseUrl}/index.min.json")
		}
		val requestUrls = indexUrls.map(::applyMirror)
		val startedAt = System.currentTimeMillis()
		Log.d(TAG, "fetchAvailableExtensions:start type=${repo.type} urls=$requestUrls")
		return try {
			val extensions = withTimeout(CATALOG_TIMEOUT_MS) {
				requestUrls.flatMap { requestUrl ->
					val body = httpClient.newCall(GET(requestUrl)).awaitSuccess().use { response ->
						response.body.string()
					}
					when (repo.type) {
						ExternalExtensionType.IREADER -> {
							val dto = json.decodeFromString<List<IReaderExtensionIndexDto>>(body)
							dto.asSequence()
								.mapNotNull { item -> item.toAvailableExtension(repo) }
								.toList()
						}
						ExternalExtensionType.CLOUDSTREAM -> {
							val dto = json.decodeFromString<List<CloudstreamPluginIndexDto>>(body)
							dto.asSequence()
								.mapNotNull { item -> item.toAvailableExtension(repo, requestUrl) }
								.toList()
						}
						else -> {
							val dto = json.decodeFromString<List<ExtensionIndexDto>>(body)
							dto.asSequence()
								.mapNotNull { item -> item.toAvailableExtension(repo) }
								.toList()
						}
					}
				}
			}
			Log.d(
				TAG,
				"fetchAvailableExtensions:success type=${repo.type} baseUrl=${repo.baseUrl} count=${extensions.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
			)
			extensions
		} catch (error: Throwable) {
			if (error is CancellationException) throw error
			Log.e(
				TAG,
				"fetchAvailableExtensions:failed type=${repo.type} baseUrl=${repo.baseUrl} elapsedMs=${System.currentTimeMillis() - startedAt} message=${error.message}",
				error,
			)
			if (repo.type == ExternalExtensionType.MIHON || repo.type == ExternalExtensionType.ANIYOMI) {
				return fetchProtobufExtensions(
					repo = repo,
					indexUrl = "${repo.baseUrl}/index.pb",
				)
			}
			throw error
		}
	}

	fun normalizeIndexUrl(input: String, type: ExternalExtensionType? = null): String? {
		val processUrl = input.trim()

		val url = processUrl.toHttpUrlOrNull() ?: return null
		if (url.scheme != "https") {
			return null
		}
		if (type == ExternalExtensionType.JAR) {
			parseGitHubRepositoryUrl(url.toString())?.let { return it.webUrl }
		}
		if (type == ExternalExtensionType.CLOUDSTREAM || looksLikeCloudstreamRepoUrl(url) || looksLikeCloudstreamRepoRootUrl(url)) {
			return url.newBuilder()
				.fragment(null)
				.query(null)
				.build()
				.toString()
		}
		val normalizedSegments = url.pathSegments
			.filter { it.isNotEmpty() }
			.toMutableList()
		val lastSegment = normalizedSegments.lastOrNull()
		if (lastSegment != "index.min.json" && lastSegment != "index.pb" && lastSegment != "plugins.json") {
			normalizedSegments += "index.min.json"
		}
		val normalizedPath = "/" + normalizedSegments.joinToString("/")
		return url.newBuilder()
			.encodedPath(normalizedPath)
			.fragment(null)
			.query(null)
			.build()
			.toString()
	}

	fun baseUrlFromIndexUrl(indexUrl: String): String {
		val url = indexUrl.toHttpUrlOrNull()
		if (url != null && isProtobufIndexUrl(url.toString())) {
			return url.newBuilder()
				.fragment(null)
				.query(null)
				.build()
				.toString()
		}
		if (url != null && looksLikeCloudstreamRepoUrl(url)) {
			return url.newBuilder()
				.fragment(null)
				.query(null)
				.build()
				.toString()
				.trimEnd('/')
		}
		return indexUrl
			.removeSuffix("/index.min.json")
			.removeSuffix("/plugins.json")
	}

	fun looksLikeResolvedCloudstreamMetadataUrl(url: String): Boolean {
		val httpUrl = url.toHttpUrlOrNull() ?: return false
		val lastSegment = httpUrl.pathSegments.filter { it.isNotEmpty() }.lastOrNull() ?: return false
		return isCloudstreamIndexEntry(lastSegment)
	}

	private fun ExtensionIndexDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension? {
		val libVersion = runCatching { version.substringBeforeLast('.').toDouble() }.getOrNull() ?: if (repo.type == ExternalExtensionType.IREADER) 0.0 else return null
		val supported = when (repo.type) {
			ExternalExtensionType.MIHON -> libVersion in MihonExtensionLoader.LIB_VERSION_MIN..MihonExtensionLoader.LIB_VERSION_MAX
			ExternalExtensionType.ANIYOMI -> libVersion in AniyomiExtensionLoader.LIB_VERSION_MIN..AniyomiExtensionLoader.LIB_VERSION_MAX
			ExternalExtensionType.IREADER -> true
			ExternalExtensionType.JAR -> true
			ExternalExtensionType.CLOUDSTREAM -> true
		}
		val displayName = when (repo.type) {
			ExternalExtensionType.MIHON -> name.removePrefix("Tachiyomi: ")
			ExternalExtensionType.ANIYOMI -> name.removePrefix("Aniyomi: ")
			ExternalExtensionType.IREADER -> name.removePrefix("IReader: ")
			ExternalExtensionType.JAR -> name
			ExternalExtensionType.CLOUDSTREAM -> name
		}

		return RepoAvailableExtension(
			type = repo.type,
			name = displayName,
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw == 1,
			sourceNames = sources.orEmpty().map { it.name },
			archiveName = apk,
			archiveUrl = null,
			iconUrl = applyMirror(if (repo.type == ExternalExtensionType.IREADER) "${repo.baseUrl}/icon/${apk.replace(".apk", ".png")}" else "${repo.baseUrl}/icon/$pkg.png"),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			signatureHash = if (repo.type == ExternalExtensionType.JAR) "" else repo.signingKeyFingerprint,
			isCompatible = supported,
		)
	}

	private suspend fun fetchProtobufExtensions(
		repo: ExternalExtensionRepo,
		indexUrl: String = repo.baseUrl,
	): List<RepoAvailableExtension> {
		val startedAt = System.currentTimeMillis()
		Log.d(TAG, "fetchAvailableExtensions:start type=${repo.type} urls=[$indexUrl]")
		return try {
			val extensions = withTimeout(CATALOG_TIMEOUT_MS) {
				val index = fetchMihonExtensionStoreIndex(indexUrl)
				val extensionList = index.extensionList ?: index.extensionListUrl?.let { listUrl ->
					fetchMihonExtensionList(pluginUrlFrom(indexUrl, listUrl))
				} ?: error("Mihon extension store does not contain an extension list")
				extensionList.extensions.mapNotNull { extension ->
					extension.toAvailableExtension(repo)
				}
			}
			Log.d(
				TAG,
				"fetchAvailableExtensions:success type=${repo.type} baseUrl=${repo.baseUrl} " +
					"count=${extensions.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
			)
			extensions
		} catch (error: Throwable) {
			Log.e(
				TAG,
				"fetchAvailableExtensions:failed type=${repo.type} baseUrl=${repo.baseUrl} " +
					"elapsedMs=${System.currentTimeMillis() - startedAt} message=${error.message}",
				error,
			)
			throw error
		}
	}

	private suspend fun fetchMihonExtensionStoreIndex(indexUrl: String): MihonExtensionStoreIndex {
		val bytes = httpClient.newCall(GET(applyMirror(indexUrl))).awaitSuccess().use { response ->
			response.body.source().decompressIfGzipped().use { source -> source.readByteArray() }
		}
		return ProtoBuf.decodeFromByteArray(MihonExtensionStoreIndex.serializer(), bytes)
	}

	private suspend fun fetchMihonExtensionList(listUrl: String): MihonExtensionStoreIndex.ExtensionList {
		val bytes = httpClient.newCall(GET(applyMirror(listUrl))).awaitSuccess().use { response ->
			response.body.source().decompressIfGzipped().use { source -> source.readByteArray() }
		}
		return ProtoBuf.decodeFromByteArray(MihonExtensionStoreIndex.ExtensionList.serializer(), bytes)
	}

	private fun MihonExtensionStoreIndex.Extension.toAvailableExtension(
		repo: ExternalExtensionRepo,
	): RepoAvailableExtension? {
		val libVersion = extensionLib.toDoubleOrNull() ?: return null
		val supported = when (repo.type) {
			ExternalExtensionType.MIHON ->
				libVersion in MihonExtensionLoader.LIB_VERSION_MIN..MihonExtensionLoader.LIB_VERSION_MAX
			ExternalExtensionType.ANIYOMI ->
				libVersion in AniyomiExtensionLoader.LIB_VERSION_MIN..AniyomiExtensionLoader.LIB_VERSION_MAX
			else -> true
		}
		val languages = sources.map { it.language }.toSet()
		val archiveUrl = applyMirror(resources.apkUrl)
		return RepoAvailableExtension(
			type = repo.type,
			name = name,
			pkgName = packageName,
			versionName = versionName,
			versionCode = versionCode,
			libVersion = libVersion,
			lang = languages.singleOrNull() ?: "all",
			isNsfw = contentWarning >= MihonExtensionStoreIndex.ContentWarning.MIXED,
			sourceNames = sources.map { it.name },
			archiveName = archiveUrl.substringAfterLast('/').substringBefore('?').ifBlank { "$packageName.apk" },
			archiveUrl = archiveUrl,
			iconUrl = applyMirror(resources.iconUrl),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			signatureHash = repo.signingKeyFingerprint,
			isCompatible = supported,
		)
	}

	private fun BufferedSource.decompressIfGzipped(): BufferedSource {
		val isGzip = peek().use { peeked ->
			runCatching { peeked.readShort().toInt() == GZIP_MAGIC }.getOrDefault(false)
		}
		return if (isGzip) gzip().buffer() else this
	}

	private fun isProtobufIndexUrl(url: String): Boolean {
		return url.toHttpUrlOrNull()
			?.pathSegments
			?.lastOrNull()
			?.equals("index.pb", ignoreCase = true) == true
	}

	private fun IReaderExtensionIndexDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension {
		val libVersion = runCatching { version.substringBeforeLast('.').toDouble() }.getOrNull() ?: 0.0
		val displayName = name.removePrefix("IReader: ")

		return RepoAvailableExtension(
			type = repo.type,
			name = displayName,
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw,
			sourceNames = emptyList(), // IReader plugins don't declare subset sources natively
			archiveName = apk,
			archiveUrl = null,
			iconUrl = applyMirror("${repo.baseUrl}/icon/${apk.replace(".apk", ".png")}"),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			// IReader repos currently don't expose a verifiable APK signing fingerprint.
			// `repo.signingKeyFingerprint` is a synthetic repo identifier for repo management,
			// not the package certificate fingerprint, so using it for trust checks would
			// always misclassify installed IReader extensions as untrusted.
			signatureHash = "",
			isCompatible = true,
		)
	}

	private fun CloudstreamPluginIndexDto.toAvailableExtension(repo: ExternalExtensionRepo, pluginListUrl: String): RepoAvailableExtension? {
		val normalizedUrl = pluginUrlFrom(pluginListUrl, url)
		val archiveName = normalizedUrl.substringAfterLast('/').ifBlank { "$internalName.cs3" }
		val normalizedLanguage = language ?: "all"
		val normalizedVersionName = version.toString()
		return RepoAvailableExtension(
			type = repo.type,
			name = name,
			pkgName = internalName,
			versionName = normalizedVersionName,
			versionCode = version.toLong(),
			libVersion = apiVersion.toDouble(),
			lang = normalizedLanguage,
			isNsfw = false,
			sourceNames = listOf(name),
			archiveName = archiveName,
			archiveUrl = normalizedUrl,
			iconUrl = iconUrl?.let { pluginUrlFrom(pluginListUrl, it) }.orEmpty(),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			// Cloudstream catalogs expose file hashes, not Android package signing fingerprints.
			// Treating fileHash as a signature would incorrectly mark every installed plugin as untrusted.
			signatureHash = "",
			isCompatible = true,
		)
	}

	private suspend fun fetchCloudstreamPluginListUrls(repo: ExternalExtensionRepo): List<String> {
		val (metadataUrl, body) = fetchCloudstreamRepoMetadata(requireNotNull(repo.baseUrl.toHttpUrlOrNull()))
		val dto = json.decodeFromString<CloudstreamRepositoryMetaDto>(body)
		return dto.pluginLists
			.map { pluginUrlFrom(metadataUrl, it) }
			.ifEmpty { listOf(pluginUrlFrom(metadataUrl, "plugins.json")) }
	}

	private suspend fun fetchCloudstreamRepoMetadata(url: okhttp3.HttpUrl): Pair<String, String> {
		var lastNotFound: HttpException? = null
		for (metadataUrl in resolveCloudstreamRepoMetadataUrls(url)) {
			try {
				val body = httpClient.newCall(GET(applyMirror(metadataUrl))).awaitSuccess().use { response ->
					response.body.string()
				}
				return metadataUrl to body
			} catch (error: HttpException) {
				if (error.code != 404) {
					throw error
				}
				lastNotFound = error
			}
		}
		discoverCloudstreamMetadataUrl(url)?.let { metadataUrl ->
			val body = httpClient.newCall(GET(applyMirror(metadataUrl))).awaitSuccess().use { response ->
				response.body.string()
			}
			return metadataUrl to body
		}
		throw checkNotNull(lastNotFound) { "No Cloudstream metadata URL candidates for $url" }
	}

	private fun resolveCloudstreamRepoMetadataUrls(url: okhttp3.HttpUrl): List<String> {
		val sanitizedUrl = url.newBuilder()
			.fragment(null)
			.query(null)
			.build()
		val lastSegment = sanitizedUrl.pathSegments.filter { it.isNotEmpty() }.lastOrNull()
		if (isCloudstreamIndexEntry(lastSegment)) {
			return listOf(sanitizedUrl.toString())
		}
		return listOf("repo.json", "repo")
			.map { suffix ->
				sanitizedUrl.newBuilder()
					.addPathSegment(suffix)
					.build()
					.toString()
			}
	}

	private fun cloudstreamRepositoryIdentityUrl(metadataUrl: String): String {
		val url = metadataUrl.toHttpUrlOrNull() ?: return metadataUrl.trimEnd('/')
		val lastSegment = url.pathSegments.filter { it.isNotEmpty() }.lastOrNull()
		if (lastSegment == null || !isCanonicalCloudstreamRepoFile(lastSegment)) {
			return metadataUrl.trimEnd('/')
		}
		val segments = url.pathSegments
			.filter { it.isNotEmpty() }
			.dropLast(1)
		val normalizedPath = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
		return url.newBuilder()
			.encodedPath(normalizedPath)
			.fragment(null)
			.query(null)
			.build()
			.toString()
			.trimEnd('/')
	}

	private fun isCanonicalCloudstreamRepoFile(segment: String): Boolean {
		val value = segment.lowercase()
		return value == "repo" || value == "repo.json"
	}

	private fun looksLikeCloudstreamRepoUrl(url: okhttp3.HttpUrl): Boolean {
		val joinedPath = url.encodedPath.lowercase()
		return joinedPath.endsWith("/repo.json") ||
			joinedPath.endsWith("/plugins.json") ||
			joinedPath.endsWith("/repo") ||
			(joinedPath.endsWith(".json") && !joinedPath.endsWith("/index.min.json"))
	}

	private fun looksLikeCloudstreamRepoRootUrl(url: okhttp3.HttpUrl): Boolean {
		val joinedPath = url.encodedPath.lowercase()
		return joinedPath.contains("/builds/") || url.host.contains("codeberg.org")
	}

	private suspend fun discoverCloudstreamMetadataUrl(url: okhttp3.HttpUrl): String? {
		val rawDirectory = parseGitHubRawDirectory(url) ?: return null
		val body = httpClient.newCall(GET(rawDirectory.contentsApiUrl)).awaitSuccess().use { response ->
			response.body.string()
		}
		val items = runCatching {
			json.decodeFromString<List<GitHubContentsItemDto>>(body)
		}.getOrNull().orEmpty()
		val candidates = items
			.asSequence()
			.filter { it.type == "file" }
			.filter { it.downloadUrl?.endsWith(".json", ignoreCase = true) == true }
			.filterNot { it.name.equals("plugins.json", ignoreCase = true) }
			.sortedWith(compareBy<GitHubContentsItemDto> { cloudstreamMetadataCandidateRank(rawDirectory, it.name) }.thenBy { it.name.length })
			.toList()
		for (candidate in candidates) {
			val downloadUrl = candidate.downloadUrl ?: continue
			val candidateBody = runCatching {
				httpClient.newCall(GET(applyMirror(downloadUrl))).awaitSuccess().use { response ->
					response.body.string()
				}
			}.getOrNull() ?: continue
			val metadata = runCatching {
				json.decodeFromString<CloudstreamRepositoryMetaDto>(candidateBody)
			}.getOrNull() ?: continue
			if (metadata.pluginLists.isNotEmpty() || metadata.manifestVersion != null || metadata.name.isNotBlank()) {
				return downloadUrl
			}
		}
		return null
	}

	private fun cloudstreamMetadataCandidateRank(directory: GitHubRawDirectory, fileName: String): Int {
		val lowerName = fileName.lowercase()
		val repoJsonName = "${directory.repo.lowercase()}.json"
		return when {
			lowerName == repoJsonName -> 0
			lowerName == "manifest.json" -> 1
			lowerName.endsWith(".json") -> 2
			else -> 3
		}
	}

	private fun parseGitHubRawDirectory(url: okhttp3.HttpUrl): GitHubRawDirectory? {
		if (url.host != "raw.githubusercontent.com") return null
		val segments = url.pathSegments.filter { it.isNotEmpty() }
		if (segments.size < 3) return null
		val owner = segments[0]
		val repo = segments[1]
		val remainder = segments.drop(2)
		val ref: String
		val directoryPathSegments: List<String>
		if (remainder.size >= 3 && remainder[0] == "refs" && remainder[1] == "heads") {
			ref = remainder[2]
			directoryPathSegments = remainder.drop(3)
		} else {
			ref = remainder[0]
			directoryPathSegments = remainder.drop(1)
		}
		val apiBuilder = okhttp3.HttpUrl.Builder()
			.scheme(url.scheme)
			.host("api.github.com")
			.port(url.port)
			.addPathSegment("repos")
			.addPathSegment(owner)
			.addPathSegment(repo)
			.addPathSegment("contents")
		directoryPathSegments.forEach { apiBuilder.addPathSegment(it) }
		apiBuilder.addQueryParameter("ref", ref)
		return GitHubRawDirectory(
			repo = repo,
			contentsApiUrl = apiBuilder.build().toString(),
		)
	}

	private fun pluginUrlFrom(baseUrl: String, url: String): String {
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return applyMirror(url)
		}
		val resolved = baseUrl.toHttpUrlOrNull()
			?.resolve(url)
			?.toString()
		return applyMirror(resolved ?: "${baseUrl.trimEnd('/')}/${url.removePrefix("/")}")
	}

	private suspend fun fetchLatestGitHubRelease(repo: GitHubRepository): GitHubReleaseDto {
		// Some user proxies accept an HTTP/2 connection but never send the SETTINGS preface.
		// GitHub's REST API supports HTTP/1.1, so keep this request scoped to the reliable protocol.
		val body = githubHttpClient.newCall(GET(repo.releasesApiUrl)).awaitSuccess().use { response ->
			response.body.string()
		}
		return json.decodeFromString<List<GitHubReleaseDto>>(body)
			.firstOrNull { release -> release.assets.any { it.name.endsWith(".jar", ignoreCase = true) } }
			?: error("GitHub repository has no published JAR release: ${repo.webUrl}")
	}

	private fun parseGitHubRepositoryUrl(value: String): GitHubRepository? {
		val url = value.toHttpUrlOrNull() ?: return null
		if (url.host != "github.com") return null
		val segments = url.pathSegments.filter { it.isNotEmpty() }
		if (segments.size < 2) return null
		val owner = segments[0]
		val repo = segments[1].removeSuffix(".git")
		if (owner.isBlank() || repo.isBlank()) return null
		return GitHubRepository(owner, repo)
	}

	private fun GitHubReleaseAssetDto.toAvailableExtension(
		repo: ExternalExtensionRepo,
		release: GitHubReleaseDto,
		githubRepo: GitHubRepository,
	): RepoAvailableExtension {
		val packageName = name.removeSuffix(".jar")
			.lowercase()
			.replace(Regex("[^a-z0-9._-]"), "-")
			.ifBlank { githubRepo.repo.lowercase() }
		return RepoAvailableExtension(
			type = ExternalExtensionType.JAR,
			name = githubRepo.repo,
			pkgName = packageName,
			versionName = release.tagName,
			versionCode = id,
			libVersion = 0.0,
			lang = "all",
			isNsfw = false,
			sourceNames = emptyList(),
			archiveName = name,
			archiveUrl = downloadUrl,
			iconUrl = "",
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			signatureHash = "",
			isCompatible = true,
		)
	}



	@Keep
	@Serializable
	private data class RepoMetaWrapperDto(
		@SerialName("index_v2")
		val indexV2: String? = null,
		val meta: RepoMetaDto,
	)

	@Keep
	@Serializable
	private data class RepoMetaDto(
		val name: String,
		@SerialName("shortName")
		val shortName: String? = null,
		val website: String,
		@SerialName("signingKeyFingerprint")
		val signingKeyFingerprint: String,
	)

	@Keep
	@Serializable
	private data class ExtensionIndexDto(
		val name: String,
		val pkg: String,
		val apk: String,
		val lang: String = "all",
		val code: Long,
		val version: String,
		val nsfw: Int = 0,
		val sources: List<ExtensionSourceDto>? = null,
	)

	@Keep
	@Serializable
	private data class ExtensionSourceDto(
		val name: String,
	)

	@Keep
	@Serializable
	private data class IReaderExtensionIndexDto(
		val name: String = "",
		val pkg: String = "",
		val apk: String = "",
		val lang: String = "en",
		val code: Long = 1,
		val version: String = "1.0",
		val nsfw: Boolean = false,
	)

	@Keep
	@Serializable
	private data class CloudstreamPluginIndexDto(
		val url: String,
		val status: Int = 1,
		val version: Int,
		val apiVersion: Int = 1,
		val name: String,
		val internalName: String,
		val authors: List<String> = emptyList(),
		val description: String? = null,
		val repositoryUrl: String? = null,
		val tvTypes: List<String>? = null,
		val language: String? = null,
		val iconUrl: String? = null,
		val fileSize: Long? = null,
		val fileHash: String? = null,
	)

	@Keep
	@Serializable
	private data class CloudstreamRepositoryMetaDto(
		val name: String = "",
		val iconUrl: String? = null,
		val description: String? = null,
		val manifestVersion: Int? = null,
		val pluginLists: List<String> = emptyList(),
		val repositoryUrl: String? = null,
		val website: String? = null,
	)

	@Keep
	@Serializable
	private data class GitHubContentsItemDto(
		val name: String,
		val type: String,
		@SerialName("download_url")
		val downloadUrl: String? = null,
	)

	private data class GitHubRawDirectory(
		val repo: String,
		val contentsApiUrl: String,
	)

	@Keep
	@Serializable
	private data class GitHubReleaseDto(
		val id: Long,
		@SerialName("tag_name")
		val tagName: String,
		val assets: List<GitHubReleaseAssetDto> = emptyList(),
	)

	@Keep
	@Serializable
	private data class GitHubReleaseAssetDto(
		val id: Long,
		val name: String,
		@SerialName("browser_download_url")
		val downloadUrl: String,
	)

	private data class GitHubRepository(
		val owner: String,
		val repo: String,
	) {
		val webUrl: String get() = "https://github.com/$owner/$repo"
		val releasesApiUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases?per_page=20"
	}

	private companion object {
		const val TAG = "ExtensionRepo"
		const val GZIP_MAGIC = 0x1f8b
		const val REPO_DETAILS_TIMEOUT_MS = 15_000L
		const val CATALOG_TIMEOUT_MS = 20_000L
	}
}
