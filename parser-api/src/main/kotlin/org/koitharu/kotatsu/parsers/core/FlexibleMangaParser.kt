package org.koitharu.kotatsu.parsers.core

import androidx.annotation.CallSuper
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Deprecated("Too complex. Use AbstractMangaParser instead")
internal abstract class FlexibleMangaParser @InternalParsersApi constructor(
	@property:InternalParsersApi val context: MangaLoaderContext,
	final override val source: MangaSource,
) : MangaParser {

	override val config: MangaSourceConfig by lazy { context.getConfig(source) }

	open val sourceLocale: Locale
		get() = if (source.locale.isEmpty()) Locale.ROOT else Locale(source.locale)

	protected open val userAgentKey: ConfigKey.UserAgent = try {
		ConfigKey.UserAgent(context.getDefaultUserAgent())
	} catch (_: NoSuchMethodError) {
		ConfigKey.UserAgent(org.koitharu.kotatsu.parsers.network.UserAgents.CHROME_MOBILE)
	}

	final override val filterCapabilities: MangaListFilterCapabilities
		get() = searchQueryCapabilities.toMangaListFilterCapabilities()

	protected val sourceContentRating: ContentRating?
		get() = if (source.contentType == ContentType.HENTAI) {
			ContentRating.ADULT
		} else {
			null
		}

	final override val domain: String
		get() = config[configKeyDomain]

	@Deprecated("Override intercept() instead")
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.build()

	/**
	 * Used as fallback if value of `order` passed to [getList] is null
	 */
	open val defaultSortOrder: SortOrder
		get() {
			val supported = availableSortOrders
			return SortOrder.entries.first { it in supported }
		}

	@JvmField
	protected val webClient: WebClient = OkHttpWebClient(context.httpClient, source)

	/**
	 * Fetch direct link to the page image.
	 */
	override suspend fun getPageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	final override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return getList(convertToMangaSearchQuery(offset, order, filter))
	}

	/**
	 * Parse favicons from the main page of the source`s website
	 */
	override suspend fun getFavicons(): Favicons {
		return FaviconParser(webClient, domain).parseFavicons()
	}

	@CallSuper
	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		keys.add(configKeyDomain)
		keys.add(userAgentKey)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		return RelatedMangaFinder(listOf(this)).invoke(seed)
	}

	/**
	 * Return [Manga] object by web link to it
	 * @see [Manga.publicUrl]
	 */
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = null

	override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
