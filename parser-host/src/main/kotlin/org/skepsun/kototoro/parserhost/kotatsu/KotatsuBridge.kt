@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package org.skepsun.kototoro.parserhost.kotatsu

import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext as KTMangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser as KTMangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap as KTBitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect as KTRect
import org.koitharu.kotatsu.parsers.config.ConfigKey as KTConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig as KTMangaSourceConfig
import org.koitharu.kotatsu.parsers.model.ContentRating as KTContentRating
import org.koitharu.kotatsu.parsers.model.ContentType as KTContentType
import org.koitharu.kotatsu.parsers.model.Demographic as KTDemographic
import org.koitharu.kotatsu.parsers.model.Favicon as KTFavicon
import org.koitharu.kotatsu.parsers.model.Favicons as KTFavicons
import org.koitharu.kotatsu.parsers.model.Manga as KTManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as KTMangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter as KTMangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities as KTMangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions as KTMangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage as KTMangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource as KTMangaSource
import org.koitharu.kotatsu.parsers.model.MangaState as KTMangaState
import org.koitharu.kotatsu.parsers.model.MangaTag as KTMangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder as KTSortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver as KTLinkResolver
import org.skepsun.kototoro.parserhost.ParserLoaderContext
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.bitmap.Rect
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.core.AbstractContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.Favicon
import org.skepsun.kototoro.parsers.model.Favicons
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Locale

/** A Kotatsu-ABI source viewed as a Kototoro [ContentSource]. */
internal data class KotatsuParserSource(val delegate: KTMangaSource) : ContentSource {
    override val name: String get() = delegate.name
    override val locale: String get() = delegate.locale
    override val contentType: ContentType get() = delegate.contentType.toKototoro()
    val title: String = (try {
        delegate.javaClass.getMethod("getTitle").invoke(delegate) as? String
    } catch (_: ReflectiveOperationException) {
        null
    }) ?: delegate.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** Presents a Kotatsu-ABI parser through the Kototoro parser contract so one runtime serves every plugin. */
internal class KotatsuContentParserAdapter(
    private val delegate: KTMangaParser,
    private val kotatsuSource: KotatsuParserSource,
    context: ContentLoaderContext,
) : AbstractContentParser(context, kotatsuSource) {

    override val availableSortOrders: Set<SortOrder> = delegate.availableSortOrders.mapTo(linkedSetOf()) { it.toKototoro() }
    override val filterCapabilities: ContentListFilterCapabilities get() = delegate.filterCapabilities.toKototoro()
    override val configKeyDomain: ConfigKey.Domain get() = ConfigKey.Domain(*delegate.configKeyDomain.presetValues)

    override suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> =
        delegate.getList(offset, order.toKotatsu(), filter.toKotatsu(kotatsuSource)).map { it.toKototoro(source) }

    override suspend fun getDetails(manga: Content): Content =
        delegate.getDetails(manga.toKotatsu(kotatsuSource)).toKototoro(source)

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> =
        delegate.getPages(chapter.toKotatsu(kotatsuSource)).map { it.toKototoro(source) }

    override suspend fun getPageUrl(page: ContentPage): String = delegate.getPageUrl(page.toKotatsu(kotatsuSource))

    override suspend fun getFilterOptions(): ContentListFilterOptions = delegate.getFilterOptions().toKototoro(source)

    override suspend fun getFavicons(): Favicons = delegate.getFavicons().toKototoro()

    override suspend fun getRelatedContent(seed: Content): List<Content> =
        delegate.getRelatedManga(seed.toKotatsu(kotatsuSource)).map { it.toKototoro(source) }

    override fun getRequestHeaders() = delegate.getRequestHeaders()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        val kotatsuKeys = ArrayList<KTConfigKey<*>>()
        delegate.onCreateConfig(kotatsuKeys)
        keys += kotatsuKeys.mapNotNull { it.toKototoro() }
    }

    override fun intercept(chain: Interceptor.Chain): Response = delegate.intercept(chain)
}

/** Gives a Kotatsu plugin the host services; unlike the Android adapter it keeps image descrambling working. */
internal class KotatsuLoaderContextAdapter(
    private val delegate: ParserLoaderContext,
    private val instantiate: (KTMangaSource, KTMangaLoaderContext) -> KTMangaParser,
) : KTMangaLoaderContext() {

    override val httpClient: OkHttpClient get() = delegate.httpClient
    override val cookieJar: CookieJar get() = delegate.cookieJar

    override fun newParserInstance(source: KTMangaSource): KTMangaParser = instantiate(source, this)

    override fun newLinkResolver(link: HttpUrl): KTLinkResolver =
        throw UnsupportedOperationException("Link resolution from Kotatsu plugins is not supported")

    override suspend fun evaluateJs(script: String): String? = delegate.evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        if (timeout > 0) withTimeout(timeout) { delegate.evaluateJs(baseUrl, script) } else delegate.evaluateJs(baseUrl, script)

    override fun requestBrowserAction(parser: KTMangaParser, url: String): Nothing =
        delegate.browserAction(KotatsuParserSource(parser.source), url)

    override fun getConfig(source: KTMangaSource): KTMangaSourceConfig =
        KotatsuConfigAdapter(delegate.config(KotatsuParserSource(source)))

    override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()
    override fun encodeBase64(data: ByteArray): String = delegate.encodeBase64(data)
    override fun decodeBase64(data: String): ByteArray = delegate.decodeBase64(data)
    override fun getPreferredLocales(): List<Locale> = delegate.getPreferredLocales()

    override fun redrawImageResponse(response: Response, redraw: (image: KTBitmap) -> KTBitmap): Response =
        delegate.redrawImageResponse(response) { bitmap ->
            val result = redraw(KotatsuBitmap(bitmap))
            requireNotNull((result as? KotatsuBitmap)?.delegate) {
                "Kotatsu image redraw must return a bitmap created by the host context"
            }
        }

    override fun createBitmap(width: Int, height: Int): KTBitmap = KotatsuBitmap(delegate.createBitmap(width, height))
}

private class KotatsuBitmap(val delegate: Bitmap) : KTBitmap {
    override val width: Int get() = delegate.width
    override val height: Int get() = delegate.height

    override fun drawBitmap(sourceBitmap: KTBitmap, src: KTRect, dst: KTRect) {
        val source = requireNotNull((sourceBitmap as? KotatsuBitmap)?.delegate)
        delegate.drawBitmap(source, Rect(src.left, src.top, src.right, src.bottom), Rect(dst.left, dst.top, dst.right, dst.bottom))
    }
}

private class KotatsuConfigAdapter(private val delegate: ContentSourceConfig) : KTMangaSourceConfig {
    override fun <T> get(key: KTConfigKey<T>): T {
        val mapped = key.toKototoro() ?: return key.defaultValue
        return delegate[mapped]
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> KTConfigKey<T>.toKototoro(): ConfigKey<T>? = when (this) {
    is KTConfigKey.Domain -> ConfigKey.Domain(*presetValues) as ConfigKey<T>
    is KTConfigKey.ShowSuspiciousContent -> ConfigKey.ShowSuspiciousContent(defaultValue) as ConfigKey<T>
    is KTConfigKey.UserAgent -> ConfigKey.UserAgent(defaultValue) as ConfigKey<T>
    is KTConfigKey.SplitByTranslations -> ConfigKey.SplitByTranslations(defaultValue) as ConfigKey<T>
    is KTConfigKey.PreferredImageServer -> ConfigKey.PreferredImageServer(presetValues, defaultValue) as ConfigKey<T>
    is KTConfigKey.InterceptCloudflare -> ConfigKey.InterceptCloudflare(defaultValue) as ConfigKey<T>
    else -> null
}

internal fun KTContentType.toKototoro(): ContentType = when (this) {
    KTContentType.MANGA -> ContentType.MANGA
    KTContentType.MANHWA -> ContentType.MANHWA
    KTContentType.MANHUA -> ContentType.MANHUA
    KTContentType.HENTAI -> ContentType.HENTAI_MANGA
    KTContentType.COMICS -> ContentType.COMICS
    KTContentType.NOVEL -> ContentType.NOVEL
    KTContentType.ONE_SHOT -> ContentType.ONE_SHOT
    KTContentType.DOUJINSHI -> ContentType.DOUJINSHI
    KTContentType.IMAGE_SET -> ContentType.IMAGE_SET
    KTContentType.ARTIST_CG -> ContentType.ARTIST_CG
    KTContentType.GAME_CG -> ContentType.GAME_CG
    KTContentType.OTHER -> ContentType.OTHER
}

internal fun ContentType.toKotatsu(): KTContentType = when (this) {
    ContentType.MANGA -> KTContentType.MANGA
    ContentType.MANHWA -> KTContentType.MANHWA
    ContentType.MANHUA -> KTContentType.MANHUA
    ContentType.HENTAI_MANGA, ContentType.HENTAI_NOVEL, ContentType.HENTAI_VIDEO -> KTContentType.HENTAI
    ContentType.COMICS -> KTContentType.COMICS
    ContentType.NOVEL -> KTContentType.NOVEL
    ContentType.VIDEO -> KTContentType.MANGA
    ContentType.ONE_SHOT -> KTContentType.ONE_SHOT
    ContentType.DOUJINSHI -> KTContentType.DOUJINSHI
    ContentType.IMAGE_SET -> KTContentType.IMAGE_SET
    ContentType.ARTIST_CG -> KTContentType.ARTIST_CG
    ContentType.GAME_CG -> KTContentType.GAME_CG
    ContentType.OTHER -> KTContentType.OTHER
}

internal fun KTContentRating.toKototoro(): ContentRating = ContentRating.valueOf(name)
internal fun ContentRating.toKotatsu(): KTContentRating = KTContentRating.valueOf(name)
internal fun KTSortOrder.toKototoro(): SortOrder = SortOrder.valueOf(name)
internal fun SortOrder.toKotatsu(): KTSortOrder = KTSortOrder.valueOf(name)

internal fun KTDemographic.toKototoro(): Demographic = Demographic.entries.firstOrNull { it.name == name } ?: Demographic.NONE
internal fun Demographic.toKotatsu(): KTDemographic = KTDemographic.entries.firstOrNull { it.name == name } ?: KTDemographic.NONE

internal fun KTMangaState?.toKototoro(): ContentState? = this?.let { ContentState.valueOf(it.name) }
internal fun ContentState.toKotatsu(): KTMangaState = KTMangaState.valueOf(name)

internal fun KTMangaTag.toKototoro(source: ContentSource) = ContentTag(title, key, source)
private fun ContentTag.toKotatsu(source: KotatsuParserSource) = KTMangaTag(title, key, source.delegate)

internal fun KTFavicon.toKototoro() = Favicon(url = url, size = size, rel = null)
internal fun KTFavicons.toKototoro() = Favicons(favicons = map { it.toKototoro() }, referer = referer)

internal fun KTManga.toKototoro(source: ContentSource) = Content(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.toKototoro(),
    coverUrl = coverUrl,
    tags = tags.mapTo(linkedSetOf()) { it.toKototoro(source) },
    state = state.toKototoro(),
    authors = authors,
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toKototoro(source) },
    source = source,
)

internal fun KTMangaChapter.toKototoro(source: ContentSource) = ContentChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = source,
)

internal fun KTMangaPage.toKototoro(source: ContentSource) = ContentPage(
    id = id,
    url = url,
    preview = preview,
    headers = null,
    source = source,
)

internal fun KTMangaListFilterOptions.toKototoro(source: ContentSource) = ContentListFilterOptions(
    availableTags = availableTags.mapTo(linkedSetOf()) { it.toKototoro(source) },
    availableStates = availableStates.mapNotNullTo(linkedSetOf()) { it.toKototoro() },
    availableContentRating = availableContentRating.mapTo(linkedSetOf()) { it.toKototoro() },
    availableContentTypes = availableContentTypes.mapTo(linkedSetOf()) { it.toKototoro() },
    availableDemographics = availableDemographics.mapTo(linkedSetOf()) { it.toKototoro() },
    availableLocales = availableLocales,
)

internal fun KTMangaListFilterCapabilities.toKototoro() = ContentListFilterCapabilities(
    isMultipleTagsSupported = isMultipleTagsSupported,
    isTagsExclusionSupported = isTagsExclusionSupported,
    isSearchSupported = isSearchSupported,
    isSearchWithFiltersSupported = isSearchWithFiltersSupported,
    isYearSupported = isYearSupported,
    isYearRangeSupported = isYearRangeSupported,
    isOriginalLocaleSupported = isOriginalLocaleSupported,
    isAuthorSearchSupported = isAuthorSearchSupported,
)

internal fun Content.toKotatsu(source: KotatsuParserSource) = KTManga(
    id = id,
    title = title,
    altTitles = altTitles,
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    contentRating = contentRating?.toKotatsu(),
    coverUrl = coverUrl,
    tags = tags.mapTo(linkedSetOf()) { it.toKotatsu(source) },
    state = state?.toKotatsu(),
    authors = authors,
    largeCoverUrl = largeCoverUrl,
    description = description,
    chapters = chapters?.map { it.toKotatsu(source) },
    source = source.delegate,
)

internal fun ContentChapter.toKotatsu(source: KotatsuParserSource) = KTMangaChapter(
    id = id,
    title = title,
    number = number,
    volume = volume,
    url = url,
    scanlator = scanlator,
    uploadDate = uploadDate,
    branch = branch,
    source = source.delegate,
)

internal fun ContentPage.toKotatsu(source: KotatsuParserSource) = KTMangaPage(
    id = id,
    url = url,
    preview = preview,
    source = source.delegate,
)

internal fun ContentListFilter.toKotatsu(source: KotatsuParserSource) = KTMangaListFilter(
    query = query,
    tags = tags.mapTo(linkedSetOf()) { it.toKotatsu(source) },
    tagsExclude = tagsExclude.mapTo(linkedSetOf()) { it.toKotatsu(source) },
    locale = locale,
    originalLocale = originalLocale,
    states = states.mapTo(linkedSetOf()) { it.toKotatsu() },
    contentRating = contentRating.mapTo(linkedSetOf()) { it.toKotatsu() },
    types = types.mapTo(linkedSetOf()) { it.toKotatsu() },
    demographics = demographics.mapTo(linkedSetOf()) { it.toKotatsu() },
    year = year,
    yearFrom = yearFrom,
    yearTo = yearTo,
    author = author,
)
