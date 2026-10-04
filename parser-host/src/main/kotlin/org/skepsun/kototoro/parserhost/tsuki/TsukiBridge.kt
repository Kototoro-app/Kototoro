@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package org.skepsun.kototoro.parserhost.tsuki

import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.parserhost.ParserLoaderContext
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
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
import org.skepsun.kototoro.parsers.util.LinkResolver
import java.util.Locale

/** A Tsuki (UMA) source viewed as a Kototoro [ContentSource]. */
internal data class TsukiContentSource(val delegate: tsuki.model.MangaSource) : ContentSource {
    override val name: String get() = delegate.name
    override val locale: String get() = delegate.locale
    override val contentType: ContentType get() = delegate.contentType.toKototoro()
    val title: String get() = delegate.title
}

/** Presents a Tsuki parser through the Kototoro parser contract so one runtime serves every plugin. */
internal class TsukiContentParserAdapter(
    private val delegate: tsuki.MangaParser,
    private val tsukiSource: TsukiContentSource,
    context: ContentLoaderContext,
) : AbstractContentParser(context, tsukiSource) {

    override val availableSortOrders: Set<SortOrder> = delegate.availableSortOrders.mapTo(linkedSetOf()) { it.toKototoro() }
    override val filterCapabilities: ContentListFilterCapabilities get() = delegate.filterCapabilities.toKototoro()
    override val configKeyDomain: ConfigKey.Domain get() = ConfigKey.Domain(*delegate.configKeyDomain.presetValues)
    override val authorizationProvider: ContentParserAuthProvider? =
        (delegate as? tsuki.MangaParserAuthProvider)?.let(::TsukiAuthProviderAdapter)

    override suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> =
        withTsukiExceptions(source) {
            delegate.getList(offset, order.toTsuki(), filter.toTsuki(tsukiSource.delegate)).map { it.toKototoro(source) }
        }

    override suspend fun getDetails(manga: Content): Content =
        withTsukiExceptions(source) { delegate.getDetails(manga.toTsuki(tsukiSource.delegate)).toKototoro(source) }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> =
        withTsukiExceptions(source) { delegate.getPages(chapter.toTsuki(tsukiSource.delegate)).map { it.toKototoro(source) } }

    override suspend fun getPageUrl(page: ContentPage): String =
        withTsukiExceptions(source) { delegate.getPageUrl(page.toTsuki(tsukiSource.delegate)) }

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        withTsukiExceptions(source) { delegate.getFilterOptions().toKototoro(source) }

    override suspend fun getFavicons(): Favicons = withTsukiExceptions(source) { delegate.getFavicons().toKototoro() }

    override suspend fun getRelatedContent(seed: Content): List<Content> =
        withTsukiExceptions(source) {
            delegate.getRelatedManga(seed.toTsuki(tsukiSource.delegate)).map { it.toKototoro(source) }
        }

    override fun getRequestHeaders() = delegate.getRequestHeaders()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        val tsukiKeys = mutableListOf<tsuki.config.ConfigKey<*>>()
        delegate.onCreateConfig(tsukiKeys)
        keys += tsukiKeys.map { it.toKototoro() }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Content? =
        withTsukiExceptions(source) { delegate.resolveLink(link)?.toKototoro(source) }

    override fun intercept(chain: Interceptor.Chain): Response = delegate.intercept(chain)
}

private class TsukiAuthProviderAdapter(private val delegate: tsuki.MangaParserAuthProvider) : ContentParserAuthProvider {
    override val authUrl: String get() = delegate.authUrl
    override suspend fun isAuthorized(): Boolean = delegate.isAuthorized()
    override suspend fun getUsername(): String = delegate.getUsername().orEmpty()
}

private suspend inline fun <T> withTsukiExceptions(source: ContentSource, block: () -> T): T {
    try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: tsuki.exception.AuthRequiredException) {
        throw org.skepsun.kototoro.parsers.exception.AuthRequiredException(source, e)
    } catch (e: tsuki.exception.NotFoundException) {
        throw org.skepsun.kototoro.parsers.exception.NotFoundException(e.message.orEmpty(), e.url)
    } catch (e: tsuki.exception.ContentUnavailableException) {
        throw org.skepsun.kototoro.parsers.exception.ContentUnavailableException(e.message.orEmpty())
    } catch (e: tsuki.exception.ParseException) {
        throw org.skepsun.kototoro.parsers.exception.ParseException(e.shortMessage, e.url, e)
    } catch (e: tsuki.exception.TooManyRequestExceptions) {
        throw org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions(e.url, e.getRetryDelay())
    }
}

/** Gives a Tsuki plugin the host services. [sources] lists what the plugin jar declares. */
internal class TsukiLoaderContextAdapter(
    private val delegate: ParserLoaderContext,
    private val sources: () -> List<tsuki.model.MangaSource>,
    private val instantiate: (tsuki.model.MangaSource, tsuki.MangaLoaderContext) -> tsuki.MangaParser,
) : tsuki.MangaLoaderContext() {

    override val httpClient: OkHttpClient get() = delegate.httpClient
    override val cookieJar: CookieJar get() = delegate.cookieJar

    override fun newParserInstance(source: tsuki.model.MangaSource): tsuki.MangaParser = instantiate(source, this)

    override fun getParserSources(): List<tsuki.model.MangaSource> = sources()

    override fun getConfig(source: tsuki.model.MangaSource): tsuki.config.MangaSourceConfig =
        TsukiConfigAdapter(delegate.config(TsukiContentSource(source)))

    override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()
    override fun encodeBase64(data: ByteArray): String = delegate.encodeBase64(data)
    override fun decodeBase64(data: String): ByteArray = delegate.decodeBase64(data)
    override fun getPreferredLocales(): List<Locale> = delegate.getPreferredLocales()

    override suspend fun evaluateJs(script: String): String? = delegate.evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = delegate.evaluateJs(baseUrl, script)

    override fun requestBrowserAction(parser: tsuki.MangaParser, url: String): Nothing =
        delegate.browserAction(TsukiContentSource(parser.source), url)

    override fun redrawImageResponse(response: Response, redraw: (image: tsuki.bitmap.Bitmap) -> tsuki.bitmap.Bitmap): Response =
        delegate.redrawImageResponse(response) { bitmap ->
            val result = redraw(TsukiBitmap(bitmap))
            requireNotNull((result as? TsukiBitmap)?.delegate) {
                "Tsuki image redraw must return a bitmap created by the host context"
            }
        }

    override fun createBitmap(width: Int, height: Int): tsuki.bitmap.Bitmap = TsukiBitmap(delegate.createBitmap(width, height))
}

private class TsukiBitmap(val delegate: Bitmap) : tsuki.bitmap.Bitmap {
    override val width: Int get() = delegate.width
    override val height: Int get() = delegate.height

    override fun drawBitmap(sourceBitmap: tsuki.bitmap.Bitmap, src: tsuki.bitmap.Rect, dst: tsuki.bitmap.Rect) {
        val source = requireNotNull((sourceBitmap as? TsukiBitmap)?.delegate)
        delegate.drawBitmap(source, src.toKototoro(), dst.toKototoro())
    }
}

private fun tsuki.bitmap.Rect.toKototoro() = Rect(left, top, right, bottom)

private class TsukiConfigAdapter(private val delegate: ContentSourceConfig) : tsuki.config.MangaSourceConfig {
    override fun <T> get(key: tsuki.config.ConfigKey<T>): T = delegate[key.toKototoro()]
}

@Suppress("UNCHECKED_CAST")
internal fun <T> tsuki.config.ConfigKey<T>.toKototoro(): ConfigKey<T> = when (this) {
    is tsuki.config.ConfigKey.Domain -> ConfigKey.Domain(*presetValues) as ConfigKey<T>
    is tsuki.config.ConfigKey.ShowSuspiciousContent -> ConfigKey.ShowSuspiciousContent(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.UserAgent -> ConfigKey.UserAgent(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.SplitByTranslations -> ConfigKey.SplitByTranslations(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.PreferredImageServer -> ConfigKey.PreferredImageServer(presetValues, defaultValue) as ConfigKey<T>
}

internal fun tsuki.model.ContentType.toKototoro(): ContentType = ContentType.entries.firstOrNull { it.name == name }
    ?: if (this == tsuki.model.ContentType.HENTAI) ContentType.HENTAI_MANGA else ContentType.OTHER

internal fun ContentType.toTsuki(): tsuki.model.ContentType = when (this) {
    ContentType.HENTAI_MANGA, ContentType.HENTAI_NOVEL, ContentType.HENTAI_VIDEO -> tsuki.model.ContentType.HENTAI
    ContentType.VIDEO -> tsuki.model.ContentType.MANGA
    else -> tsuki.model.ContentType.entries.firstOrNull { it.name == name } ?: tsuki.model.ContentType.OTHER
}

internal fun tsuki.model.ContentRating.toKototoro() = ContentRating.valueOf(name)
internal fun ContentRating.toTsuki() = tsuki.model.ContentRating.valueOf(name)
internal fun tsuki.model.SortOrder.toKototoro() = SortOrder.valueOf(name)
internal fun SortOrder.toTsuki() = tsuki.model.SortOrder.valueOf(name)
internal fun tsuki.model.Demographic.toKototoro() = Demographic.entries.firstOrNull { it.name == name } ?: Demographic.NONE
internal fun Demographic.toTsuki() = tsuki.model.Demographic.entries.firstOrNull { it.name == name } ?: tsuki.model.Demographic.NONE
internal fun tsuki.model.MangaState?.toKototoro() = this?.let { ContentState.valueOf(it.name) }
internal fun ContentState.toTsuki() = tsuki.model.MangaState.valueOf(name)

internal fun tsuki.model.MangaTag.toKototoro(source: ContentSource) = ContentTag(title, key, source)
internal fun ContentTag.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaTag(title, key, source)

internal fun tsuki.model.Manga.toKototoro(source: ContentSource) = Content(
    id, title, altTitles, url, publicUrl, rating, contentRating?.toKototoro(), coverUrl,
    tags.mapTo(linkedSetOf()) { it.toKototoro(source) }, state.toKototoro(), authors,
    largeCoverUrl, description, chapters?.map { it.toKototoro(source) }, source,
)

internal fun tsuki.model.MangaChapter.toKototoro(source: ContentSource) = ContentChapter(
    id, title, number, volume, url, scanlator, uploadDate, branch, source,
)

internal fun tsuki.model.MangaPage.toKototoro(source: ContentSource) = ContentPage(id, url, preview, null, source)

internal fun Content.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.Manga(
    id, title, altTitles, url, publicUrl, rating, contentRating?.toTsuki(), coverUrl,
    tags.mapTo(linkedSetOf()) { it.toTsuki(source) }, state?.toTsuki(), authors,
    largeCoverUrl, description, chapters?.map { it.toTsuki(source) }, source,
)

internal fun ContentChapter.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaChapter(
    id, title, number, volume, url, scanlator, uploadDate, branch, source,
)

internal fun ContentPage.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaPage(id, url, preview, source)

internal fun ContentListFilter.toTsuki(source: tsuki.model.MangaSource) = tsuki.model.MangaListFilter(
    query, tags.mapTo(linkedSetOf()) { it.toTsuki(source) },
    tagsExclude.mapTo(linkedSetOf()) { it.toTsuki(source) }, locale, originalLocale,
    states.mapTo(linkedSetOf()) { it.toTsuki() }, contentRating.mapTo(linkedSetOf()) { it.toTsuki() },
    types.mapTo(linkedSetOf()) { it.toTsuki() }, demographics.mapTo(linkedSetOf()) { it.toTsuki() },
    year, yearFrom, yearTo, author,
)

internal fun tsuki.model.MangaListFilterOptions.toKototoro(source: ContentSource) = ContentListFilterOptions(
    availableTags = availableTags.mapTo(linkedSetOf()) { it.toKototoro(source) },
    availableStates = availableStates.mapNotNullTo(linkedSetOf()) { it.toKototoro() },
    availableContentRating = availableContentRating.mapTo(linkedSetOf()) { it.toKototoro() },
    availableContentTypes = availableContentTypes.mapTo(linkedSetOf()) { it.toKototoro() },
    availableDemographics = availableDemographics.mapTo(linkedSetOf()) { it.toKototoro() },
    availableLocales = availableLocales,
)

internal fun tsuki.model.MangaListFilterCapabilities.toKototoro() = ContentListFilterCapabilities(
    isMultipleTagsSupported, isTagsExclusionSupported, isSearchSupported, isSearchWithFiltersSupported,
    isYearSupported, isYearRangeSupported, isOriginalLocaleSupported, isAuthorSearchSupported,
)

internal fun tsuki.model.Favicons.toKototoro() = Favicons(map { Favicon(it.url, it.size, null) }, referer)
