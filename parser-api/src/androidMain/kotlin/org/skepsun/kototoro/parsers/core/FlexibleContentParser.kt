package org.skepsun.kototoro.parsers.core

import androidx.annotation.CallSuper
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.network.OkHttpWebClient
import org.skepsun.kototoro.parsers.network.WebClient
import org.skepsun.kototoro.parsers.util.*
import java.util.*

@Deprecated("Too complex. Use AbstractContentParser instead")
internal abstract class FlexibleContentParser @InternalParsersApi constructor(
	@property:InternalParsersApi val context: ContentLoaderContext,
	final override val source: ContentSource,
) : ContentParser {

	override val config: ContentSourceConfig by lazy { context.getConfig(source) }

	open val sourceLocale: Locale
		get() = if (source.locale.isEmpty()) Locale.ROOT else Locale(source.locale)

	protected open val userAgentKey: ConfigKey.UserAgent = try {
		ConfigKey.UserAgent(context.getDefaultUserAgent())
	} catch (_: NoSuchMethodError) {
		ConfigKey.UserAgent(org.skepsun.kototoro.parsers.network.UserAgents.CHROME_MOBILE)
	}

	final override val filterCapabilities: ContentListFilterCapabilities
		get() = searchQueryCapabilities.toContentListFilterCapabilities()

	protected val sourceContentRating: ContentRating?
		get() = if (source.contentType == ContentType.HENTAI_MANGA) {
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
	override suspend fun getPageUrl(page: ContentPage): String = page.url.toAbsoluteUrl(domain)

	final override suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
		return getList(convertToContentSearchQuery(offset, order, filter))
	}

	protected open val faviconDomain: String
		get() = domain

	/**
	 * Parse favicons from the main page of the source`s website
	 */
	override suspend fun getFavicons(): Favicons {
		return FaviconParser(webClient, faviconDomain).parseFavicons()
	}

	@CallSuper
	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		keys.add(configKeyDomain)
		keys.add(userAgentKey)
	}

	override suspend fun getRelatedContent(seed: Content): List<Content> {
		return RelatedContentFinder(listOf(this)).invoke(seed)
	}

	/**
	 * Return [Content] object by web link to it
	 * @see [Content.publicUrl]
	 */
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Content? = null

	override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
