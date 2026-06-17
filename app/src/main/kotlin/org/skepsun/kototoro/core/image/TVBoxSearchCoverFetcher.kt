package org.skepsun.kototoro.core.image

import android.util.Log
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.getAvailableRepositoryOrNull
import org.skepsun.kototoro.core.util.ext.mangaKey
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

private const val TAG = "TVBoxSearchCoverFetcher"

data class TVBoxSearchCoverModel(
    val manga: Content,
)

class TVBoxSearchCoverFetcher(
    private val manga: Content,
    private val repositoryFactory: ContentRepository.Factory,
    private val fallbackImageClient: OkHttpClient,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        Log.d(
            TAG,
            "fetch start: source=${manga.source.name} title=${manga.title} mangaUrl=${manga.url}",
        )
        val repo = repositoryFactory.createWithDiagnostics(manga.source).getAvailableRepositoryOrNull(
            tag = "TVBoxSearchCoverFetcher",
            prefix = "repository_unavailable",
        ) ?: run {
            Log.w(TAG, "fetch abort: repository unavailable for source=${manga.source.name} title=${manga.title}")
            return null
        }
        val details = repo.getDetails(manga)
        Log.d(
            TAG,
            "details resolved: source=${manga.source.name} title=${manga.title} detailsCover=${details.coverUrl ?: "<null>"} detailsLargeCover=${details.largeCoverUrl ?: "<null>"}",
        )
        val coverUrl = details.coverUrl?.takeIf { it.isNotBlank() } ?: details.largeCoverUrl?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "fetch abort: no cover from details for source=${manga.source.name} title=${manga.title}")
                return null
            }
        val imageClient = repo.getImageClient() ?: fallbackImageClient
        Log.d(
            TAG,
            "delegating to ContentCoverFetcher: source=${manga.source.name} title=${manga.title} coverUrl=$coverUrl",
        )
        return ContentCoverFetcher(
            imageUrl = coverUrl,
            options = options,
            imageClient = imageClient,
            repo = repo,
            cacheDir = options.fileSystem,
        ).fetch()
    }

    class Factory @Inject constructor(
        private val repositoryFactory: ContentRepository.Factory,
        @ContentHttpClient private val fallbackImageClient: OkHttpClient,
    ) : Fetcher.Factory<TVBoxSearchCoverModel> {

        override fun create(data: TVBoxSearchCoverModel, options: Options, imageLoader: ImageLoader): Fetcher? {
            val manga = options.extras[mangaKey] ?: data.manga
            if (!manga.url.startsWith("tvbox://item/")) {
                Log.w(TAG, "create abort: manga url is not tvbox item, title=${manga.title} mangaUrl=${manga.url}")
                return null
            }
            Log.d(
                TAG,
                "create success: source=${manga.source.name} title=${manga.title} mangaUrl=${manga.url}",
            )
            return TVBoxSearchCoverFetcher(
                manga = manga,
                repositoryFactory = repositoryFactory,
                fallbackImageClient = fallbackImageClient,
                options = options,
            )
        }
    }
}

fun tvboxSearchCoverModel(manga: Content): TVBoxSearchCoverModel = TVBoxSearchCoverModel(manga)
