package org.skepsun.kototoro.core.parser.favicon

import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import coil3.ColorImage
import org.json.JSONObject
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import coil3.toBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toOkioPath
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.parser.EmptyContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.legado.LegadoRepository
import org.skepsun.kototoro.core.parser.external.ExternalContentRepository
import org.skepsun.kototoro.core.parser.JsContentRepository
import org.skepsun.kototoro.mihon.MihonMangaRepository
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.fetch
import org.skepsun.kototoro.core.util.ext.mangaSourceKey
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.local.data.FaviconCache
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import coil3.Uri as CoilUri
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamContentRepository

import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.settings.sources.extensions.normalizePackageNameForMatching

class FaviconFetcher(
	private val uri: Uri,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val localStorageCache: LocalStorageCache,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		if (uri.isHierarchical) uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { iconUrl ->
			val sourceId = uri.authority ?: uri.schemeSpecificPart
			logFaviconResolve(
				stage = "direct_start",
				sourceId = sourceId,
				source = options.extras[mangaSourceKey]?.unwrap(),
				directIconUrl = iconUrl,
			)
			return fetchDirectFavicon(sourceId, iconUrl)
		}
		val sourceId = uri.schemeSpecificPart.let {
			if (it.startsWith("JSON_") && it.endsWith("_json")) {
				it.removeSuffix("_json")
			} else {
				it
			}
		}
		val extraSource = options.extras[mangaSourceKey]?.unwrap()
		val mangaSource = extraSource ?: if (sourceId.startsWith("JSON_")) {
			val jsonSources = runBlocking {
				jsonSourceManager.observeAllJsonSources().first().map {
					org.skepsun.kototoro.core.jsonsource.JsonContentSource(it)
				}
			}
			jsonSources.find { it.name == sourceId } ?: org.skepsun.kototoro.core.model.ContentSource(sourceId)
		} else {
			org.skepsun.kototoro.core.model.ContentSource(sourceId)
		}
		logFaviconResolve(
			stage = "source_resolved",
			sourceId = sourceId,
			source = mangaSource,
			usedExtra = extraSource != null,
		)

		val repo = mangaRepositoryFactory.create(mangaSource)
		logFaviconResolve(
			stage = "repository_created",
			sourceId = sourceId,
			source = mangaSource,
			repository = repo,
		)
		return when (repo) {
			is ParserContentRepository -> fetchParserFavicon(repo)
			is KotatsuParserRepository -> fetchKotatsuFavicon(repo)
			is ExternalContentRepository -> fetchPluginIcon(repo)
			is EmptyContentRepository -> ImageFetchResult(
				image = ColorImage(Color.WHITE),
				isSampled = false,
				dataSource = DataSource.MEMORY,
			)
			
			// JSON/Legado sources: try to derive favicon from bookSourceUrl in config
			is LegadoRepository -> {
				val config = (repo.source as? JsonContentSource)?.entity?.config
				val siteUrl = try {
					config?.let { JSONObject(it).optString("bookSourceUrl") }
				} catch (e: Exception) {
					null
				}
				val faviconUrl = siteUrl?.let { url ->
					val uri = Uri.parse(url)
					if (uri.scheme != null && uri.host != null) {
						"${uri.scheme}://${uri.host}/favicon.ico"
					} else null
				}
				faviconUrl?.let { url ->
					runCatchingCancellable { imageLoader.fetch(url, options) }.getOrNull()
				} ?: fetchDefaultIcon(mangaSource)
			}

			is LocalMangaRepository -> imageLoader.fetch(R.drawable.ic_storage, options)

			is MihonMangaRepository -> fetchMihonIcon(repo)

			is org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository -> fetchAniyomiIcon(repo)

			is org.skepsun.kototoro.ireader.IReaderMangaRepository -> fetchIReaderIcon(repo)

			is CloudstreamContentRepository -> fetchCloudstreamIcon(repo)

			// JS sources: try to derive favicon from config; fallback to neutral
			is JsContentRepository -> {
				val guessed = guessFaviconUrl((repo.source as? JsonContentSource)?.entity?.config)
				guessed?.let { url ->
					runCatchingCancellable { imageLoader.fetch(url, options) }.getOrNull()
				} ?: fetchDefaultIcon(mangaSource)
			}

			// LNReader sources: extract icon from JS code config
			is org.skepsun.kototoro.core.lnreader.LNReaderContentRepository -> {
				val configStr = (repo.source as? JsonContentSource)?.entity?.config
				val iconUrl = (repo.source as? JsonContentSource)
					?.entity
					?.iconUrl
					?.normalizeLnReaderIconUrl()
					?: try {
						configStr?.let {
							org.skepsun.kototoro.core.lnreader.LNReaderPluginMetadata
								.extractFromCode(it, "")
								?.icon
								?.normalizeLnReaderIconUrl()
						}
					} catch (e: Exception) {
						null
					}
				iconUrl?.let { url ->
					runCatchingCancellable { imageLoader.fetch(url, options) }.getOrNull()
				} ?: fetchDefaultIcon(mangaSource)
			}

			else -> fetchDefaultIcon(mangaSource)
		}.also {
			logFaviconResolve(
				stage = "fetch_complete",
				sourceId = sourceId,
				source = mangaSource,
				repository = repo,
				result = it,
			)
		}
	}

	private suspend fun fetchDirectFavicon(sourceId: String, iconUrl: String): FetchResult? {
		val sizePx = maxOf(
			options.size.width.pxOrElse { FALLBACK_SIZE },
			options.size.height.pxOrElse { FALLBACK_SIZE },
		)
		val cacheKey = options.diskCacheKey ?: "${sourceId}_${iconUrl.hashCode()}_$sizePx"
		if (options.diskCachePolicy.readEnabled) {
			localStorageCache[cacheKey]?.let { file ->
				logFaviconCache("hit", sourceId, cacheKey, file.length())
				return file.asFetchResult()
			}
			logFaviconCache("miss", sourceId, cacheKey)
		} else {
			logFaviconCache("read_disabled", sourceId, cacheKey)
		}
		val result = imageLoader.fetch(iconUrl, options) ?: return null
		logFaviconCache("downstream_${result.dataSourceName()}", sourceId, cacheKey)
		return if (options.diskCachePolicy.writeEnabled) {
			writeToCache(cacheKey, result)
		} else {
			logFaviconCache("write_disabled", sourceId, cacheKey)
			result
		}
	}

	private fun logFaviconResolve(
		stage: String,
		sourceId: String,
		source: ParserContentSource? = null,
		usedExtra: Boolean? = null,
		directIconUrl: String? = null,
		repository: ContentRepository? = null,
		result: FetchResult? = null,
	) {
		if (sourceId.startsWith("JSON_") && directIconUrl.isNullOrBlank()) return
		android.util.Log.d(
			"FaviconFetcher",
			buildString {
				append("stage=").append(stage)
				append(" sourceId=").append(sourceId)
				source?.let {
					append(" source=").append(it.name)
					append(" sourceType=").append(it.javaClass.name)
				}
				usedExtra?.let { append(" usedExtra=").append(it) }
				directIconUrl?.let { append(" directUrl=").append(it) }
				repository?.let {
					append(" repository=").append(it.javaClass.name)
				}
				result?.let {
					append(" result=").append(it.javaClass.simpleName)
					append(" dataSource=").append(it.dataSourceName())
				}
			},
		)
	}

	private fun logExtensionIcon(
		stage: String,
		sourceName: String,
		packageName: String,
		iconUrl: String? = null,
		error: Throwable? = null,
	) {
		android.util.Log.d(
			"FaviconFetcher",
			buildString {
				append("stage=").append(stage)
				append(" source=").append(sourceName)
				append(" package=").append(packageName)
				iconUrl?.let { append(" iconUrl=").append(it) }
				error?.let {
					append(" error=").append(it.javaClass.name)
					append(":").append(it.message)
				}
			},
		)
	}

	private suspend fun fetchDefaultIcon(mangaSource: ParserContentSource): FetchResult? {
		return imageLoader.fetch(defaultIconRes(mangaSource), options)
	}

    private suspend fun fetchMihonIcon(repository: MihonMangaRepository): FetchResult {
        val pm = options.context.packageManager
        val pkgName = repository.source.pkgName
        logExtensionIcon("mihon_start", repository.source.name, pkgName)

        try {
            val availableExtensions = repoRepository.getAvailableExtensions(ExternalExtensionType.MIHON)
            val repoExt = availableExtensions.find { it.pkgName == pkgName }
            if (repoExt != null && repoExt.iconUrl.isNotBlank()) {
                val remoteIcon = imageLoader.fetch(repoExt.iconUrl, options)
                if (remoteIcon != null) {
                    logExtensionIcon("mihon_remote_hit", repository.source.name, pkgName, repoExt.iconUrl)
                    return remoteIcon
                }
                logExtensionIcon("mihon_remote_null", repository.source.name, pkgName, repoExt.iconUrl)
            }
        } catch (e: Exception) {
            logExtensionIcon("mihon_remote_error", repository.source.name, pkgName, error = e)
            e.printStackTraceDebug()
        }

        val icon = runInterruptible {
            try {
                pm.getApplicationIcon(pkgName)
            } catch (e: Exception) {
                null
            }
        }
        return if (icon != null) {
            logExtensionIcon("mihon_package_icon", repository.source.name, pkgName)
            ImageFetchResult(
                image = icon.nonAdaptive().asImage(),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        } else {
            logExtensionIcon("mihon_fallback", repository.source.name, pkgName)
            imageLoader.fetch(R.drawable.ic_storage, options)!!
        }
    }

    private suspend fun fetchCloudstreamIcon(repository: CloudstreamContentRepository): FetchResult {
        val pkgName = repository.source.pluginPackageName

        try {
            val availableExtensions = repoRepository.getCatalogExtensions(ExternalExtensionType.CLOUDSTREAM)
            val repoExt = availableExtensions.find { extension ->
                ExternalExtensionType.CLOUDSTREAM.normalizePackageNameForMatching(extension.pkgName) ==
                    ExternalExtensionType.CLOUDSTREAM.normalizePackageNameForMatching(pkgName)
            }
            if (repoExt != null && repoExt.iconUrl.isNotBlank()) {
                val remoteIcon = imageLoader.fetch(repoExt.iconUrl, options)
                if (remoteIcon != null) {
                    return remoteIcon
                }
            }
        } catch (e: Exception) {
            e.printStackTraceDebug()
        }

        return imageLoader.fetch(R.drawable.ic_source_cloudstream, options)!!
    }

    private suspend fun fetchAniyomiIcon(repository: org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository): FetchResult {
        val pm = options.context.packageManager
        val pkgName = repository.source.pkgName
        logExtensionIcon("aniyomi_start", repository.source.name, pkgName)

        try {
            val availableExtensions = repoRepository.getAvailableExtensions(ExternalExtensionType.ANIYOMI)
            val repoExt = availableExtensions.find { it.pkgName == pkgName }
            if (repoExt != null && repoExt.iconUrl.isNotBlank()) {
                val remoteIcon = imageLoader.fetch(repoExt.iconUrl, options)
                if (remoteIcon != null) {
                    logExtensionIcon("aniyomi_remote_hit", repository.source.name, pkgName, repoExt.iconUrl)
                    return remoteIcon
                }
                logExtensionIcon("aniyomi_remote_null", repository.source.name, pkgName, repoExt.iconUrl)
            }
        } catch (e: Exception) {
            logExtensionIcon("aniyomi_remote_error", repository.source.name, pkgName, error = e)
            e.printStackTraceDebug()
        }

        val icon = runInterruptible {
            try {
                pm.getApplicationIcon(pkgName)
            } catch (e: Exception) {
                null
            }
        }
        return if (icon != null) {
            logExtensionIcon("aniyomi_package_icon", repository.source.name, pkgName)
            ImageFetchResult(
                image = icon.nonAdaptive().asImage(),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        } else {
            logExtensionIcon("aniyomi_fallback", repository.source.name, pkgName)
            imageLoader.fetch(R.drawable.ic_storage, options)!!
        }
    }

    private suspend fun fetchIReaderIcon(repository: org.skepsun.kototoro.ireader.IReaderMangaRepository): FetchResult {
        val pm = options.context.packageManager
        val pkgName = repository.source.pkgName
        logExtensionIcon("ireader_start", repository.source.name, pkgName)

        // IReader extensions often don't package an icon in their APK.
        // Try to fetch the icon URL from the fetched repo extensions first.
        try {
            val availableExtensions = repoRepository.getAvailableExtensions(ExternalExtensionType.IREADER)
            val normalizedInstalledPkg = ExternalExtensionType.IREADER.normalizePackageNameForMatching(pkgName)
            val repoExt = availableExtensions.find { extension ->
                ExternalExtensionType.IREADER.normalizePackageNameForMatching(extension.pkgName) == normalizedInstalledPkg
            }
            if (repoExt != null && repoExt.iconUrl.isNotBlank()) {
                val remoteIcon = imageLoader.fetch(repoExt.iconUrl, options)
                if (remoteIcon != null) {
                    logExtensionIcon("ireader_remote_hit", repository.source.name, pkgName, repoExt.iconUrl)
                    return remoteIcon
                }
                logExtensionIcon("ireader_remote_null", repository.source.name, pkgName, repoExt.iconUrl)
            }
        } catch (e: Exception) {
            logExtensionIcon("ireader_remote_error", repository.source.name, pkgName, error = e)
            e.printStackTraceDebug()
        }

        val icon = runInterruptible {
            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                if (appInfo.icon != 0) {
                    pm.getApplicationIcon(appInfo)
                } else null
            } catch (e: Exception) {
                null
            }
        }
        return if (icon != null) {
            logExtensionIcon("ireader_package_icon", repository.source.name, pkgName)
            ImageFetchResult(
                image = icon.nonAdaptive().asImage(),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        } else {
            logExtensionIcon("ireader_fallback", repository.source.name, pkgName)
            imageLoader.fetch(R.drawable.ic_storage, options)!!
        }
    }

	private suspend fun fetchKotatsuFavicon(repository: KotatsuParserRepository): FetchResult {
		val sizePx = maxOf(
			options.size.width.pxOrElse { FALLBACK_SIZE },
			options.size.height.pxOrElse { FALLBACK_SIZE },
		)
		val cacheKey = options.diskCacheKey ?: "${repository.source.name}_$sizePx"
		if (options.diskCachePolicy.readEnabled) {
			localStorageCache[cacheKey]?.let { file ->
				logFaviconCache("hit", repository.source.name, cacheKey, file.length())
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
					mimeType = MimeTypes.probeMimeType(file)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
			logFaviconCache("miss", repository.source.name, cacheKey)
		} else {
			logFaviconCache("read_disabled", repository.source.name, cacheKey)
		}
		var favicons = repository.getFavicons()
		var lastError: Exception? = null
		while (favicons.isNotEmpty()) {
			currentCoroutineContext().ensureActive()
			val icon = favicons.find(sizePx) ?: throwNSEE(lastError)
			try {
				android.util.Log.e("DetailsFavicon", "Fetching kotatsu parser URL: ${icon.url}")
				val result = imageLoader.fetch(icon.url, options)
				if (result != null) {
					logFaviconCache("downstream_${result.dataSourceName()}", repository.source.name, cacheKey)
					return if (options.diskCachePolicy.writeEnabled) {
						writeToCache(cacheKey, result)
					} else {
						logFaviconCache("write_disabled", repository.source.name, cacheKey)
						result
					}
				} else {
					android.util.Log.e("DetailsFavicon", "Result was null for kotatsu: ${icon.url}")
					favicons -= icon
				}
			} catch (e: CloudFlareProtectedException) {
				android.util.Log.e("DetailsFavicon", "CloudFlareProtectedException: ${e.message}")
				throw e
			} catch (e: IOException) {
				android.util.Log.e("DetailsFavicon", "IOException: ${e.message}")
				lastError = e
				favicons -= icon
			}
		}
		android.util.Log.e("DetailsFavicon", "throwNSEE fallback with error: $lastError")
		throwNSEE(lastError)
	}

	private suspend fun fetchParserFavicon(repository: ParserContentRepository): FetchResult {
		val sizePx = maxOf(
			options.size.width.pxOrElse { FALLBACK_SIZE },
			options.size.height.pxOrElse { FALLBACK_SIZE },
		)
		val cacheKey = options.diskCacheKey ?: "${repository.source.name}_$sizePx"
		if (options.diskCachePolicy.readEnabled) {
			localStorageCache[cacheKey]?.let { file ->
				logFaviconCache("hit", repository.source.name, cacheKey, file.length())
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
					mimeType = MimeTypes.probeMimeType(file)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
			logFaviconCache("miss", repository.source.name, cacheKey)
		} else {
			logFaviconCache("read_disabled", repository.source.name, cacheKey)
		}
		var favicons = repository.getFavicons()
		var lastError: Exception? = null
		while (favicons.isNotEmpty()) {
			currentCoroutineContext().ensureActive()
			val icon = favicons.find(sizePx) ?: throwNSEE(lastError)
			try {
				android.util.Log.e("DetailsFavicon", "Fetching parser URL: ${icon.url}")
				val result = imageLoader.fetch(icon.url, options)
				if (result != null) {
					logFaviconCache("downstream_${result.dataSourceName()}", repository.source.name, cacheKey)
					return if (options.diskCachePolicy.writeEnabled) {
						writeToCache(cacheKey, result)
					} else {
						logFaviconCache("write_disabled", repository.source.name, cacheKey)
						result
					}
				} else {
					android.util.Log.e("DetailsFavicon", "Result was null for parser: ${icon.url}")
					favicons -= icon
				}
			} catch (e: CloudFlareProtectedException) {
				android.util.Log.e("DetailsFavicon", "CloudFlareProtectedException: ${e.message}")
				throw e
			} catch (e: IOException) {
				android.util.Log.e("DetailsFavicon", "IOException: ${e.message}")
				lastError = e
				favicons -= icon
			}
		}
		android.util.Log.e("DetailsFavicon", "throwNSEE fallback with error: $lastError")
		throwNSEE(lastError)
	}

	private suspend fun fetchPluginIcon(repository: ExternalContentRepository): FetchResult {
		val source = repository.source
		val pm = options.context.packageManager
		val icon = runInterruptible {
			val provider = pm.resolveContentProvider(source.authority, 0)
			provider?.loadIcon(pm) ?: pm.getApplicationIcon(source.packageName)
		}
		return ImageFetchResult(
			image = icon.nonAdaptive().asImage(),
			isSampled = false,
			dataSource = DataSource.DISK,
		)
	}

	private suspend fun writeToCache(key: String, result: FetchResult): FetchResult = runCatchingCancellable {
		when (result) {
			is ImageFetchResult -> {
				localStorageCache.set(key, result.image.toBitmap()).also {
					logFaviconCache("write_bitmap_${result.dataSource}", key = key, bytes = it.length())
				}.asFetchResult()
			}

			is SourceFetchResult -> {
				result.source.source().use {
					localStorageCache.set(key, it, result.mimeType?.toMimeTypeOrNull()).also { file ->
						logFaviconCache("write_source_${result.dataSource}", key = key, bytes = file.length())
					}.asFetchResult()
				}
			}
		}
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(result)

	private fun File.asFetchResult() = SourceFetchResult(
		source = ImageSource(toOkioPath(), FileSystem.SYSTEM),
		mimeType = MimeTypes.probeMimeType(this)?.toString(),
		dataSource = DataSource.DISK,
	)

	private fun FetchResult.dataSourceName(): String = when (this) {
		is ImageFetchResult -> dataSource.name
		is SourceFetchResult -> dataSource.name
	}

	class Factory @Inject constructor(
		private val mangaRepositoryFactory: ContentRepository.Factory,
		@FaviconCache private val faviconCache: LocalStorageCache,
		private val repoRepository: ExternalExtensionRepoRepository,
		private val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager,
	) : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
			imageLoader: ImageLoader
		): Fetcher? = if (data.scheme == URI_SCHEME_FAVICON) {
			FaviconFetcher(data.toAndroidUri(), options, imageLoader, mangaRepositoryFactory, faviconCache, repoRepository, jsonSourceManager)
		} else {
			null
		}
	}

	private companion object {

		const val FALLBACK_SIZE = 9999 // largest icon

		private fun logFaviconCache(
			event: String,
			source: String? = null,
			key: String,
			bytes: Long? = null,
		) {
			android.util.Log.d(
				"FaviconCache",
				buildString {
					append(event)
					source?.let { append(" source=").append(it) }
					append(" key=").append(key)
					bytes?.let { append(" bytes=").append(it) }
				},
			)
		}

		private fun defaultIconRes(source: ParserContentSource): Int {
			return when {
				source is JsonContentSource || source.name.startsWith("JSON_") -> R.drawable.ic_source_builtin
				source.name.startsWith("CLOUDSTREAM_") -> R.drawable.ic_source_cloudstream
				else -> R.drawable.ic_storage
			}
		}

		private fun guessFaviconUrl(config: String?): String? {
			if (config.isNullOrBlank()) return null
			val urlRegex = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
			val keyRegex = Regex(
				"\"(?:homepage|homePage|root|baseUrl|base_url|defaultBaseUrl|apiUrl|api_base|baseURL)\"\\s*:\\s*\"(https?://[^\"]+)\"",
				RegexOption.IGNORE_CASE
			)
			val candidates = mutableListOf<String>()
			keyRegex.findAll(config).forEach { m -> candidates.add(m.groupValues.getOrNull(1).orEmpty()) }
			urlRegex.findAll(config).forEach { m -> candidates.add(m.value) }
			val site = candidates.firstOrNull { isLikelySiteUrl(it) } ?: return null
			val uri = Uri.parse(site)
			if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
			return "${uri.scheme}://${uri.host}/favicon.ico"
		}

		private fun isLikelySiteUrl(url: String?): Boolean {
			if (url.isNullOrBlank()) return false
			if (!url.startsWith("http://") && !url.startsWith("https://")) return false
			if (url.endsWith(".js", true)) return false
			val lowered = url.lowercase()
			val blacklist = listOf(
				"venera-configs",
				"raw.githubusercontent.com",
				"git.nyne.dev",
				"gitlab",
				"/raw/",
				"/blob/",
			)
			if (blacklist.any { lowered.contains(it) }) return false
			return Uri.parse(url).host?.isNotBlank() == true
		}

		private fun String.normalizeLnReaderIconUrl(): String? {
			val value = trim().takeIf { it.isNotBlank() } ?: return null
			if (value.startsWith("http://") || value.startsWith("https://")) return value
			return value
				.removePrefix("/")
				.takeIf { it.startsWith("src/") || it.startsWith("multisrc/") }
				?.let { "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/public/static/$it" }
		}

		private fun throwNSEE(lastError: Exception?): Nothing {
			if (lastError != null) {
				throw lastError
			} else {
				throw NoSuchElementException("No favicons found")
			}
		}

		private fun Drawable.nonAdaptive() =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this is AdaptiveIconDrawable) {
				LayerDrawable(arrayOf(background, foreground))
			} else {
				this
			}

	}
}
